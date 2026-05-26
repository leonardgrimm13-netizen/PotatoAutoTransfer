package de.potato.autotransfer;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

@Plugin(id = "potatoautotransfer", name = "PotatoAutoTransfer", version = "1.2.0", authors = {"leonardgrimm13-netizen"})
public final class PotatoAutoTransfer {
  private static final String CONFIG_FILE = "config.properties";
  private static final int DEFAULT_PROTOCOL_VERSION = -1;
  private static final String DEFAULT_CONFIG = buildDefaultConfig();

  private final ProxyServer proxy;
  private final Logger logger;
  private final Path dataDir;

  private final AtomicReference<PluginConfig> config = new AtomicReference<>(PluginConfig.defaults());
  private final AtomicReference<ReachabilityState> reachability = new AtomicReference<>(ReachabilityState.unknown("not checked yet"));
  private final AtomicBoolean checkInProgress = new AtomicBoolean(false);
  private final ConcurrentHashMap<UUID, Long> lastAttemptMs = new ConcurrentHashMap<>();

  private volatile ScheduledTask repeatingTask;
  private volatile boolean hostConfiguredLogged;
  private volatile boolean commandRegistered;

  @Inject
  public PotatoAutoTransfer(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
    this.proxy = Objects.requireNonNull(proxy);
    this.logger = Objects.requireNonNull(logger);
    this.dataDir = Objects.requireNonNull(dataDir);
  }

  @Subscribe
  public void onInit(ProxyInitializeEvent ignored) {
    try {
      ensureDefaultConfigExists();
      reloadConfigInternal();
      registerCommands();
      performReachabilityCheck(true);
    } catch (Exception e) {
      logger.error("[PotatoAutoTransfer] Initialization failed", e);
    }
  }

  @Subscribe
  public void onPostLogin(PostLoginEvent event) {
    PluginConfig cfg = config.get();
    if (!cfg.autoTransfer || !cfg.isTransferHostConfigured()) {
      return;
    }
    int delayMs = cfg.joinDelayMs();
    proxy.getScheduler()
      .buildTask(this, () -> transferIfEligible(event.getPlayer(), config.get()))
      .delay(delayMs, TimeUnit.MILLISECONDS)
      .schedule();
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    lastAttemptMs.remove(event.getPlayer().getUniqueId());
  }

  private void registerCommands() {
    TransferCommand command = new TransferCommand();
    List<String> attempts = List.of("transfer", "potatotransfer", "autotransfer");
    for (String primary : attempts) {
      if (tryRegister(primary, command)) {
        commandRegistered = true;
        break;
      }
    }
    if (!commandRegistered) {
      logger.error("[PotatoAutoTransfer] Could not register any command alias.");
    }
  }

  private boolean tryRegister(String primary, SimpleCommand command) {
    try {
      CommandMeta meta = proxy.getCommandManager().metaBuilder(primary).plugin(this).build();
      proxy.getCommandManager().register(meta, command);
      logger.info("[PotatoAutoTransfer] Command active: /{}", primary);
      return true;
    } catch (IllegalArgumentException ex) {
      logger.warn("[PotatoAutoTransfer] Command /{} already in use.", primary);
      return false;
    }
  }

  private synchronized void startScheduler() {
    if (repeatingTask != null) {
      repeatingTask.cancel();
      repeatingTask = null;
    }
    PluginConfig cfg = config.get();
    if (cfg.checkIntervalSeconds < 1) {
      logger.warn("[PotatoAutoTransfer] check_interval_seconds<1, scheduler disabled.");
      return;
    }
    repeatingTask = proxy.getScheduler().buildTask(this, () -> {
      performReachabilityCheck(false);
      if (config.get().autoTransfer) {
        transferAllEligible();
      }
    }).repeat(cfg.checkIntervalSeconds, TimeUnit.SECONDS).schedule();
  }

  private void performReachabilityCheck(boolean forceLog) {
    if (!checkInProgress.compareAndSet(false, true)) {
      return;
    }
    try {
      PluginConfig cfg = config.get();
      ReachabilityState old = reachability.get();
      ReachabilityState now = evaluateReachability(cfg);
      reachability.set(now);
      if (forceLog || old.reachable != now.reachable) {
        logger.info("[PotatoAutoTransfer] Check target state changed: {} ({})", now.reachable ? "ONLINE" : "OFFLINE", now.detail);
      } else if (cfg.debug && !now.reachable) {
        logger.debug("[PotatoAutoTransfer] Reachability check failed: {}", now.detail);
      }
    } finally {
      checkInProgress.set(false);
    }
  }

  private ReachabilityState evaluateReachability(PluginConfig cfg) {
    if (!cfg.isCheckHostConfigured() || !cfg.isTransferHostConfigured()) {
      if (!hostConfiguredLogged) {
        logger.warn("[PotatoAutoTransfer] transfer_host/check_host are not configured. Set both in config.properties.");
        hostConfiguredLogged = true;
      }
      return ReachabilityState.offline("transfer_host/check_host not configured");
    }
    hostConfiguredLogged = false;
    return cfg.checkMode.equals("minecraft_status")
      ? (isMinecraftStatusReachable(cfg) ? ReachabilityState.online("minecraft status ok") : ReachabilityState.offline("minecraft status failed"))
      : (checkTcpReachable(cfg) ? ReachabilityState.online("tcp connect ok") : ReachabilityState.offline("tcp connect failed"));
  }

  private boolean checkTcpReachable(PluginConfig cfg) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(cfg.checkHost, cfg.checkPort), cfg.connectTimeoutMs);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private boolean isMinecraftStatusReachable(PluginConfig cfg) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(cfg.checkHost, cfg.checkPort), cfg.connectTimeoutMs);
      socket.setSoTimeout(cfg.readTimeoutMs);
      socket.getOutputStream().write(buildHandshakePacket(cfg));
      socket.getOutputStream().write(new byte[] {0x01, 0x00});
      InputStream in = socket.getInputStream();
      readVarInt(in); // packet len
      int packetId = readVarInt(in);
      if (packetId != 0x00) return false;
      String json = readString(in);
      return json.contains("{") && json.contains("}");
    } catch (Exception e) {
      if (cfg.debug) logger.debug("[PotatoAutoTransfer] minecraft_status check failed", e);
      return false;
    }
  }

  private byte[] buildHandshakePacket(PluginConfig cfg) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    writeVarInt(body, 0x00);
    writeVarInt(body, cfg.minecraftProtocolVersion == 0 ? DEFAULT_PROTOCOL_VERSION : cfg.minecraftProtocolVersion);
    writeString(body, cfg.checkHost);
    body.write((cfg.checkPort >>> 8) & 0xFF);
    body.write(cfg.checkPort & 0xFF);
    writeVarInt(body, 1);

    byte[] payload = body.toByteArray();
    ByteArrayOutputStream packet = new ByteArrayOutputStream();
    writeVarInt(packet, payload.length);
    packet.write(payload);
    return packet.toByteArray();
  }

  private boolean isTargetReachableCached() {
    ReachabilityState state = reachability.get();
    long ageMs = System.currentTimeMillis() - state.checkedAtMs;
    long maxAge = Math.max(1000L, config.get().checkIntervalSeconds * 2000L);
    if (state.checkedAtMs == 0 || ageMs > maxAge) {
      performReachabilityCheck(false);
      state = reachability.get();
    }
    return state.reachable;
  }

  private void transferAllEligible() {
    PluginConfig cfg = config.get();
    if (!cfg.isTransferHostConfigured() || !cfg.isCheckHostConfigured() || !isTargetReachableCached()) return;
    for (Player player : proxy.getAllPlayers()) {
      transferIfEligible(player, cfg);
    }
  }

  private boolean transferIfEligible(Player player, PluginConfig cfg) {
    if (player == null || !player.isActive() || !isCooldownOver(player.getUniqueId(), cfg)) return false;
    if (!isTargetReachableCached()) {
      if (cfg.notifyPlayersWhenTargetDown) player.sendMessage(Component.text(cfg.targetDownMessage));
      return false;
    }
    markAttempt(player.getUniqueId());
    try {
      player.transferToHost(InetSocketAddress.createUnresolved(cfg.transferHost, cfg.transferPort));
      return true;
    } catch (IllegalArgumentException e) {
      if (cfg.debug) {
        logger.debug("[PotatoAutoTransfer] Transfer not supported for {}: {}", player.getUsername(), e.getMessage());
      }
      return false;
    } catch (Exception e) {
      logger.warn("[PotatoAutoTransfer] Transfer failed for {}", player.getUsername(), e);
      return false;
    }
  }

  private boolean isCooldownOver(UUID id, PluginConfig cfg) {
    return System.currentTimeMillis() - lastAttemptMs.getOrDefault(id, 0L) >= cfg.retryCooldownSeconds * 1000L;
  }

  private void markAttempt(UUID id) { lastAttemptMs.put(id, System.currentTimeMillis()); }

  private Path configPath() { return dataDir.resolve(CONFIG_FILE); }

  private void ensureDefaultConfigExists() throws IOException {
    Files.createDirectories(dataDir);
    if (!Files.exists(configPath())) Files.writeString(configPath(), DEFAULT_CONFIG, StandardCharsets.UTF_8);
  }

  private synchronized void reloadConfigInternal() throws IOException {
    Properties p = new Properties();
    try (var r = Files.newBufferedReader(configPath(), StandardCharsets.UTF_8)) { p.load(r); }
    PluginConfig cfg = PluginConfig.fromProperties(p, logger);
    config.set(cfg);
    startScheduler();
  }

  private synchronized void setAutoTransferAndSave(boolean enabled) throws IOException {
    String content = Files.readString(configPath(), StandardCharsets.UTF_8);
    String updated = content.replaceAll("(?m)^autotransfer\\s*=.*$", "autotransfer=" + enabled);
    if (updated.equals(content)) updated = "autotransfer=" + enabled + "\n" + content;
    Files.writeString(configPath(), updated, StandardCharsets.UTF_8);
    reloadConfigInternal();
  }

  private static int readVarInt(InputStream in) throws IOException { int numRead=0,result=0,read; do { read=in.read(); if (read==-1) throw new IOException("EOF"); int value=(read & 0b01111111); result |= (value << (7 * numRead)); numRead++; if (numRead>5) throw new IOException("VarInt too big"); } while((read & 0b10000000)!=0); return result; }
  private static void writeVarInt(ByteArrayOutputStream out,int value){ do { byte temp=(byte)(value & 0b01111111); value >>>= 7; if(value!=0) temp|=0b10000000; out.write(temp);} while(value!=0);}  
  private static void writeString(ByteArrayOutputStream out,String s){ byte[] bytes=s.getBytes(StandardCharsets.UTF_8); writeVarInt(out,bytes.length); out.writeBytes(bytes);}  
  private static String readString(InputStream in) throws IOException { int len=readVarInt(in); byte[] data=readFully(in,len); return new String(data,StandardCharsets.UTF_8);}  
  private static byte[] readFully(InputStream in,int len) throws IOException { byte[] data=new byte[len]; int off=0; while(off<len){ int r=in.read(data,off,len-off); if(r==-1) throw new IOException("EOF"); off+=r;} return data; }

  private record ReachabilityState(boolean reachable, long checkedAtMs, String detail) {
    static ReachabilityState online(String detail){ return new ReachabilityState(true,System.currentTimeMillis(),detail); }
    static ReachabilityState offline(String detail){ return new ReachabilityState(false,System.currentTimeMillis(),detail); }
    static ReachabilityState unknown(String detail){ return new ReachabilityState(false,0,detail); }
  }

  private record PluginConfig(boolean autoTransfer, String transferHost, int transferPort, String checkHost, int checkPort, String checkMode, int checkIntervalSeconds,
                              int connectTimeoutMs, int readTimeoutMs, int retryCooldownSeconds, int joinDelayMs,
                              boolean notifyPlayersWhenTargetDown, String targetDownMessage, boolean debug, int minecraftProtocolVersion) {
    static PluginConfig defaults(){ return new PluginConfig(true,"CHANGE_ME",25565,"CHANGE_ME",25565,"minecraft_status",5,1000,1500,15,500,false,
      "Zielserver ist aktuell nicht erreichbar. Bitte versuche es später erneut.",false,DEFAULT_PROTOCOL_VERSION); }
    boolean isTransferHostConfigured(){ return isHostConfigured(transferHost); }
    boolean isCheckHostConfigured(){ return isHostConfigured(checkHost); }
    private static boolean isHostConfigured(String host){ return host!=null && !host.isBlank() && !host.equalsIgnoreCase("CHANGE_ME") && !host.contains("http://") && !host.contains("https://") && !host.contains(" "); }
    static PluginConfig fromProperties(Properties p, Logger logger){ PluginConfig d=defaults();
      String legacyHost = p.getProperty("target_host");
      String transferHost = readHostProperty(p, "transfer_host", d.transferHost);
      String checkHost = readHostProperty(p, "check_host", d.checkHost);
      int legacyPort = boundedInt(p.getProperty("target_port"), d.transferPort, 1, 65535, "target_port", logger);
      int transferPort = readPortProperty(p, "transfer_port", d.transferPort, logger);
      int checkPort = readPortProperty(p, "check_port", d.checkPort, logger);
      if ((transferHost.equals(d.transferHost) || checkHost.equals(d.checkHost)) && legacyHost != null && !legacyHost.isBlank()) {
        String trimmedLegacyHost = legacyHost.trim();
        if (transferHost.equals(d.transferHost)) transferHost = trimmedLegacyHost;
        if (checkHost.equals(d.checkHost)) checkHost = trimmedLegacyHost;
        if (p.getProperty("transfer_port") == null) transferPort = legacyPort;
        if (p.getProperty("check_port") == null) checkPort = legacyPort;
        logger.warn("[PotatoAutoTransfer] target_host/target_port are deprecated. Please migrate to transfer_host/transfer_port and check_host/check_port.");
      }
      int interval = boundedInt(p.getProperty("check_interval_seconds"), d.checkIntervalSeconds, 1, Integer.MAX_VALUE, "check_interval_seconds", logger);
      int cto = boundedInt(p.getProperty("connect_timeout_ms"), d.connectTimeoutMs, 100, Integer.MAX_VALUE, "connect_timeout_ms", logger);
      int rto = boundedInt(p.getProperty("read_timeout_ms"), d.readTimeoutMs, 100, Integer.MAX_VALUE, "read_timeout_ms", logger);
      int retry = boundedInt(p.getProperty("retry_cooldown_seconds"), d.retryCooldownSeconds, 0, Integer.MAX_VALUE, "retry_cooldown_seconds", logger);
      int join = boundedInt(p.getProperty("join_delay_ms"), d.joinDelayMs, 0, Integer.MAX_VALUE, "join_delay_ms", logger);
      int protocol = intOrDefault(p.getProperty("minecraft_protocol_version"), d.minecraftProtocolVersion);
      String mode = p.getProperty("check_mode", d.checkMode).trim().toLowerCase(); if (!mode.equals("tcp") && !mode.equals("minecraft_status")) mode = d.checkMode;
      return new PluginConfig(Boolean.parseBoolean(p.getProperty("autotransfer", String.valueOf(d.autoTransfer))), transferHost, transferPort, checkHost, checkPort, mode, interval, cto, rto, retry, join,
        Boolean.parseBoolean(p.getProperty("notify_players_when_target_down", String.valueOf(d.notifyPlayersWhenTargetDown))),
        p.getProperty("target_down_message", d.targetDownMessage), Boolean.parseBoolean(p.getProperty("debug", String.valueOf(d.debug))), protocol);
    }
  }

  private static String readHostProperty(Properties p, String key, String def) {
    String value = p.getProperty(key);
    return value == null ? def : value.trim();
  }

  private static int readPortProperty(Properties p, String key, int def, Logger logger) {
    return boundedInt(p.getProperty(key), def, 1, 65535, key, logger);
  }

  private static int boundedInt(String value, int def, int min, int max, String key, Logger logger) {
    int parsed = intOrDefault(value, def);
    if (parsed < min || parsed > max) { logger.warn("[PotatoAutoTransfer] Invalid {}. Using default {}.", key, def); return def; }
    return parsed;
  }
  private static int intOrDefault(String value, int def){ try { return Integer.parseInt(value.trim()); } catch(Exception e){ return def; } }

  private static String buildDefaultConfig() {
    return """
# PotatoAutoTransfer config
# Ziel ist ein externer Minecraft/Velocity-Proxy, NICHT ein Server aus velocity.toml.
# Wenn autotransfer=true ist, werden Spieler automatisch transferiert, sobald der Zielserver erreichbar ist.
autotransfer=true

# Zieladresse für player.transferToHost(...), muss vom SPIELER erreichbar sein.
transfer_host=CHANGE_ME
transfer_port=25565

# Adresse für Reachability-Checks, muss nur vom FALLBACK-Server erreichbar sein.
check_host=CHANGE_ME
check_port=25565

# Reachability check:
# tcp = nur TCP-Port offen
# minecraft_status = echter Minecraft Status-Ping, empfohlen
check_mode=minecraft_status

# Wie oft der Zielserver geprüft wird
check_interval_seconds=5

# Timeouts
connect_timeout_ms=1000
read_timeout_ms=1500

# Cooldown pro Spieler nach Transfer-Versuch
retry_cooldown_seconds=15

# Delay nach Login, bevor transferToHost ausgelöst wird
join_delay_ms=500

# Spieler informieren, falls Ziel nicht erreichbar ist
notify_players_when_target_down=false
target_down_message=Mainserver ist aktuell noch nicht erreichbar. Bitte warte kurz.

# Minecraft protocol version for status ping (-1 = auto/default)
minecraft_protocol_version=-1

# Debug-Logs
debug=false
""";
  }

  private final class TransferCommand implements SimpleCommand {
    @Override public void execute(Invocation invocation) {
      CommandSource src = invocation.source(); String[] args = invocation.arguments();
      if (args.length == 0) { if (!has(src, "potatoautotransfer.transfer")) return; PluginConfig cfg=config.get(); if (!isTargetReachableCached()) { src.sendMessage(Component.text("Kein Transfer: check target ist nicht erreichbar.")); return; } int planned=0; for(Player p:proxy.getAllPlayers()) if(transferIfEligible(p,cfg)) planned++; src.sendMessage(Component.text("Geplante Transfers: "+planned)); return; }
      String sub=args[0].toLowerCase();
      switch (sub) {
        case "status" -> { if (!has(src, "potatoautotransfer.status")) return; ReachabilityState s=reachability.get(); long age=s.checkedAtMs==0?-1:(System.currentTimeMillis()-s.checkedAtMs); PluginConfig cfg=config.get(); src.sendMessage(Component.text("autotransfer="+cfg.autoTransfer+", check="+cfg.checkHost+":"+cfg.checkPort+", transfer="+cfg.transferHost+":"+cfg.transferPort+", mode="+cfg.checkMode+", reachable="+s.reachable+", ageMs="+age+", detail="+s.detail)); }
        case "reload" -> { if (!has(src, "potatoautotransfer.admin")) return; try { reloadConfigInternal(); performReachabilityCheck(true); src.sendMessage(Component.text("Config reloaded.")); } catch (Exception e) { src.sendMessage(Component.text("Reload failed: "+e.getMessage())); }}
        case "on", "off" -> { if (!has(src, "potatoautotransfer.admin")) return; try { setAutoTransferAndSave(sub.equals("on")); src.sendMessage(Component.text("autotransfer="+sub.equals("on"))); } catch (Exception e) { src.sendMessage(Component.text("Save failed: "+e.getMessage())); }}
        case "help" -> src.sendMessage(Component.text("/transfer [status|reload|on|off|help]"));
        default -> src.sendMessage(Component.text("/transfer help"));
      }
    }
    @Override public boolean hasPermission(Invocation invocation) { String[] a=invocation.arguments(); CommandSource s=invocation.source(); if (s instanceof ConsoleCommandSource) return true; if (a.length==0) return s.hasPermission("potatoautotransfer.transfer"); return switch(a[0].toLowerCase()){ case "status" -> s.hasPermission("potatoautotransfer.status"); case "reload","on","off" -> s.hasPermission("potatoautotransfer.admin"); default -> true;}; }
    private boolean has(CommandSource src,String perm){ if (src instanceof ConsoleCommandSource || src.hasPermission(perm)) return true; src.sendMessage(Component.text("No permission: "+perm)); return false; }
  }
}
