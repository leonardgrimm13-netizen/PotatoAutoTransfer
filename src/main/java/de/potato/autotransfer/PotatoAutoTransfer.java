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
import java.util.ArrayList;
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

@Plugin(id = "potatoautotransfer", name = "PotatoAutoTransfer", version = "1.3.0", authors = {"leonardgrimm13-netizen"})
public final class PotatoAutoTransfer {
  private static final String CONFIG_FILE = "config.properties";
  private static final int DEFAULT_PROTOCOL_VERSION = -1;
  private static final String DEFAULT_CONFIG = buildDefaultConfig();

  private final ProxyServer proxy;
  private final Logger logger;
  private final Path dataDir;

  private final AtomicReference<PluginConfig> config = new AtomicReference<>(PluginConfig.defaults());
  private final AtomicReference<ReachabilityState> reachability = new AtomicReference<>(ReachabilityState.unknown());
  private final AtomicBoolean checkInProgress = new AtomicBoolean(false);
  private final ConcurrentHashMap<UUID, Long> lastAttemptMs = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Boolean> warnedKeys = new ConcurrentHashMap<>();
  private final AtomicBoolean legacyCheckWarned = new AtomicBoolean(false);
  private final AtomicBoolean invalidModeWarned = new AtomicBoolean(false);

  private volatile ScheduledTask repeatingTask;
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
    if (!cfg.autoTransfer) return;
    proxy.getScheduler().buildTask(this, () -> transferIfEligible(event.getPlayer(), config.get()))
      .delay(cfg.joinDelayMs(), TimeUnit.MILLISECONDS).schedule();
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) { lastAttemptMs.remove(event.getPlayer().getUniqueId()); }

  private void performReachabilityCheck(boolean forceLog) {
    if (!checkInProgress.compareAndSet(false, true)) return;
    try {
      PluginConfig cfg = config.get();
      ReachabilityState old = reachability.get();
      ReachabilityState now = evaluateReachability(cfg);
      reachability.set(now);
      if (forceLog || old.allRequiredChecksReachable != now.allRequiredChecksReachable) {
        if (now.allRequiredChecksReachable) {
          logger.info("[PotatoAutoTransfer] All required checks are ONLINE. Transfers are allowed.");
        } else {
          logger.info("[PotatoAutoTransfer] Required checks are not fully online. Transfers are blocked.");
        }
      }
      if (cfg.debug) {
        for (CheckState c : now.checks) {
          logger.debug("[PotatoAutoTransfer] {} enabled={}, configured={}, reachable={}, detail={}", c.id, c.enabled, c.configured, c.reachable, c.detail);
        }
      }
    } finally { checkInProgress.set(false); }
  }

  private ReachabilityState evaluateReachability(PluginConfig cfg) {
    long now = System.currentTimeMillis();
    List<CheckState> checks = new ArrayList<>();
    checks.add(evaluateCheck("check1", cfg.check1, cfg));
    checks.add(evaluateCheck("check2", cfg.check2, cfg));

    boolean transferConfigured = cfg.isTransferTargetConfigured();
    if (!transferConfigured) warnOnce("transfer_not_configured", "[PotatoAutoTransfer] transfer_host is not configured.");

    boolean allRequired = transferConfigured;
    for (CheckState s : checks) {
      if (s.enabled && !s.reachable) allRequired = false;
    }
    return new ReachabilityState(allRequired, now, checks, transferConfigured);
  }

  private CheckState evaluateCheck(String id, CheckTarget check, PluginConfig cfg) {
    long now = System.currentTimeMillis();
    if (!check.enabled) return new CheckState(id, check.name, false, true, true, now, "disabled", check.host, check.port, check.mode);
    if (!PluginConfig.isHostConfigured(check.host)) {
      String msg = String.format("[PotatoAutoTransfer] %s_host is not configured but %s_enabled=true.", id, id);
      warnOnce(id + "_host_missing", msg);
      return new CheckState(id, check.name, true, false, false, now, id + " host not configured", check.host, check.port, check.mode);
    }
    if (check.port < 1 || check.port > 65535) {
      warnOnce(id + "_port_invalid", "[PotatoAutoTransfer] " + id + "_port is invalid.");
      return new CheckState(id, check.name, true, false, false, now, id + " port invalid", check.host, check.port, check.mode);
    }
    String mode = normalizeMode(check.mode, id);
    boolean reachable = mode.equals("tcp") ? checkTcpReachable(check, cfg) : isMinecraftStatusReachable(check, cfg);
    String detail = mode.equals("tcp") ? (reachable ? "tcp connect ok" : "tcp connect failed") : (reachable ? "minecraft status ok" : "minecraft status failed");
    return new CheckState(id, check.name, true, true, reachable, now, detail, check.host, check.port, mode);
  }

  private String normalizeMode(String mode, String id) {
    String m = mode == null ? "minecraft_status" : mode.trim().toLowerCase();
    if (!m.equals("tcp") && !m.equals("minecraft_status")) {
      if (invalidModeWarned.compareAndSet(false, true)) {
        logger.warn("[PotatoAutoTransfer] Invalid check mode detected ({}). Falling back to minecraft_status.", id);
      }
      return "minecraft_status";
    }
    return m;
  }

  private boolean checkTcpReachable(CheckTarget check, PluginConfig cfg) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(check.host, check.port), cfg.connectTimeoutMs);
      return true;
    } catch (IOException e) { return false; }
  }

  private boolean isMinecraftStatusReachable(CheckTarget check, PluginConfig cfg) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(check.host, check.port), cfg.connectTimeoutMs);
      socket.setSoTimeout(cfg.readTimeoutMs);
      socket.getOutputStream().write(buildHandshakePacket(check, cfg));
      socket.getOutputStream().write(new byte[]{0x01, 0x00});
      InputStream in = socket.getInputStream();
      readVarInt(in); int packetId = readVarInt(in); if (packetId != 0x00) return false;
      String json = readString(in);
      return json.contains("{") && json.contains("}");
    } catch (Exception e) {
      if (cfg.debug) logger.debug("[PotatoAutoTransfer] minecraft_status check failed for {}", check.name, e);
      return false;
    }
  }

  private byte[] buildHandshakePacket(CheckTarget check, PluginConfig cfg) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    writeVarInt(body, 0x00);
    writeVarInt(body, cfg.minecraftProtocolVersion == 0 ? DEFAULT_PROTOCOL_VERSION : cfg.minecraftProtocolVersion);
    writeString(body, check.host);
    body.write((check.port >>> 8) & 0xFF); body.write(check.port & 0xFF); writeVarInt(body, 1);
    byte[] payload = body.toByteArray(); ByteArrayOutputStream packet = new ByteArrayOutputStream();
    writeVarInt(packet, payload.length); packet.write(payload); return packet.toByteArray();
  }

  private boolean isTargetReachableCached() {
    ReachabilityState state = reachability.get();
    long ageMs = System.currentTimeMillis() - state.checkedAtMs;
    long maxAge = Math.max(1000L, config.get().checkIntervalSeconds * 2000L);
    if (state.checkedAtMs == 0 || ageMs > maxAge) { performReachabilityCheck(false); state = reachability.get(); }
    return state.allRequiredChecksReachable;
  }

  private void transferAllEligible() { for (Player p : proxy.getAllPlayers()) transferIfEligible(p, config.get()); }

  private boolean transferIfEligible(Player player, PluginConfig cfg) {
    if (player == null || !player.isActive() || !isCooldownOver(player.getUniqueId(), cfg)) return false;
    if (!isTargetReachableCached()) {
      if (cfg.notifyPlayersWhenTargetDown) player.sendMessage(Component.text(cfg.targetDownMessage));
      return false;
    }
    markAttempt(player.getUniqueId());
    try { player.transferToHost(InetSocketAddress.createUnresolved(cfg.transferHost, cfg.transferPort)); return true; }
    catch (Exception e) { if (cfg.debug) logger.debug("[PotatoAutoTransfer] Transfer failed for {}", player.getUsername(), e); return false; }
  }

  private void registerCommands() { TransferCommand command = new TransferCommand(); for (String p : List.of("transfer", "potatotransfer", "autotransfer")) if (tryRegister(p, command)) { commandRegistered = true; break; } if (!commandRegistered) logger.error("[PotatoAutoTransfer] Could not register any command alias."); }
  private boolean tryRegister(String primary, SimpleCommand command) { try { CommandMeta meta = proxy.getCommandManager().metaBuilder(primary).plugin(this).build(); proxy.getCommandManager().register(meta, command); logger.info("[PotatoAutoTransfer] Command active: /{}", primary); return true; } catch (IllegalArgumentException ex) { logger.warn("[PotatoAutoTransfer] Command /{} already in use.", primary); return false; } }
  private boolean isCooldownOver(UUID id, PluginConfig cfg) { return System.currentTimeMillis() - lastAttemptMs.getOrDefault(id, 0L) >= cfg.retryCooldownSeconds * 1000L; }
  private void markAttempt(UUID id) { lastAttemptMs.put(id, System.currentTimeMillis()); }
  private Path configPath() { return dataDir.resolve(CONFIG_FILE); }
  private void ensureDefaultConfigExists() throws IOException { Files.createDirectories(dataDir); if (!Files.exists(configPath())) Files.writeString(configPath(), DEFAULT_CONFIG, StandardCharsets.UTF_8); }
  private synchronized void reloadConfigInternal() throws IOException { Properties p = new Properties(); try (var r = Files.newBufferedReader(configPath(), StandardCharsets.UTF_8)) { p.load(r); } config.set(PluginConfig.fromProperties(p, logger, legacyCheckWarned)); startScheduler(); }
  private synchronized void setAutoTransferAndSave(boolean enabled) throws IOException { String content = Files.readString(configPath(), StandardCharsets.UTF_8); String updated = content.replaceAll("(?m)^autotransfer\\s*=.*$", "autotransfer=" + enabled); if (updated.equals(content)) updated = "autotransfer=" + enabled + "\n" + content; Files.writeString(configPath(), updated, StandardCharsets.UTF_8); reloadConfigInternal(); }
  private synchronized void startScheduler() { if (repeatingTask != null) { repeatingTask.cancel(); repeatingTask = null; } PluginConfig cfg = config.get(); if (cfg.checkIntervalSeconds < 1) return; repeatingTask = proxy.getScheduler().buildTask(this, () -> { performReachabilityCheck(false); if (config.get().autoTransfer) transferAllEligible(); }).repeat(cfg.checkIntervalSeconds, TimeUnit.SECONDS).schedule(); }
  private void warnOnce(String key, String msg) { if (warnedKeys.putIfAbsent(key, true) == null) logger.warn(msg); }

  private static int readVarInt(InputStream in) throws IOException { int numRead=0,result=0,read; do { read=in.read(); if (read==-1) throw new IOException("EOF"); int value=(read & 0b01111111); result |= (value << (7 * numRead)); numRead++; if (numRead>5) throw new IOException("VarInt too big"); } while((read & 0b10000000)!=0); return result; }
  private static void writeVarInt(ByteArrayOutputStream out,int value){ do { byte temp=(byte)(value & 0b01111111); value >>>= 7; if(value!=0) temp|=0b10000000; out.write(temp);} while(value!=0);}  
  private static void writeString(ByteArrayOutputStream out,String s){ byte[] bytes=s.getBytes(StandardCharsets.UTF_8); writeVarInt(out,bytes.length); out.writeBytes(bytes);}  
  private static String readString(InputStream in) throws IOException { int len=readVarInt(in); byte[] data=readFully(in,len); return new String(data,StandardCharsets.UTF_8);}  
  private static byte[] readFully(InputStream in,int len) throws IOException { byte[] data=new byte[len]; int off=0; while(off<len){ int r=in.read(data,off,len-off); if(r==-1) throw new IOException("EOF"); off+=r;} return data; }

  private record CheckTarget(boolean enabled, String name, String host, int port, String mode) {}
  private record CheckState(String id, String name, boolean enabled, boolean configured, boolean reachable, long checkedAtMs, String detail, String host, int port, String mode) {}
  private record ReachabilityState(boolean allRequiredChecksReachable, long checkedAtMs, List<CheckState> checks, boolean transferConfigured) { static ReachabilityState unknown(){ return new ReachabilityState(false, 0, List.of(), false);} }

  private record PluginConfig(boolean autoTransfer, String transferHost, int transferPort, CheckTarget check1, CheckTarget check2, int checkIntervalSeconds, int connectTimeoutMs, int readTimeoutMs, int retryCooldownSeconds, int joinDelayMs, boolean notifyPlayersWhenTargetDown, String targetDownMessage, boolean debug, int minecraftProtocolVersion) {
    static PluginConfig defaults(){ return new PluginConfig(true, "CHANGE_ME", 25565, new CheckTarget(true, "Main Velocity", "CHANGE_ME", 25565, "minecraft_status"), new CheckTarget(false, "Paper Main", "CHANGE_ME", 25565, "minecraft_status"), 5, 1000, 1500, 15, 500, false, "Mainserver ist aktuell noch nicht vollständig erreichbar. Bitte warte kurz.", false, DEFAULT_PROTOCOL_VERSION); }
    boolean isTransferTargetConfigured(){ return isHostConfigured(transferHost) && transferPort >= 1 && transferPort <= 65535; }
    static boolean isHostConfigured(String host){ return host != null && !host.isBlank() && !host.equalsIgnoreCase("CHANGE_ME") && !host.contains("http://") && !host.contains("https://") && !host.contains(" "); }
    static PluginConfig fromProperties(Properties p, Logger logger, AtomicBoolean legacyWarned){ PluginConfig d = defaults();
      String transferHost = readHostProperty(p, "transfer_host", d.transferHost); int transferPort = readPortProperty(p, "transfer_port", d.transferPort, logger);
      String legacyCheckHost = p.getProperty("check_host"); String check1Host = readHostProperty(p, "check1_host", legacyCheckHost != null ? legacyCheckHost : d.check1.host);
      int check1Port = p.getProperty("check1_port") != null ? readPortProperty(p, "check1_port", d.check1.port, logger) : readPortProperty(p, "check_port", d.check1.port, logger);
      String check1Mode = p.getProperty("check1_mode", p.getProperty("check_mode", d.check1.mode));
      if ((p.getProperty("check_host") != null || p.getProperty("check_port") != null || p.getProperty("check_mode") != null) && legacyWarned.compareAndSet(false, true)) logger.warn("[PotatoAutoTransfer] Legacy check_host/check_port/check_mode detected. Please migrate to check1_host/check1_port/check1_mode.");
      CheckTarget c1 = new CheckTarget(Boolean.parseBoolean(p.getProperty("check1_enabled", "true")), p.getProperty("check1_name", d.check1.name), check1Host, check1Port, check1Mode);
      CheckTarget c2 = new CheckTarget(Boolean.parseBoolean(p.getProperty("check2_enabled", "false")), p.getProperty("check2_name", d.check2.name), readHostProperty(p, "check2_host", d.check2.host), readPortProperty(p, "check2_port", d.check2.port, logger), p.getProperty("check2_mode", d.check2.mode));
      return new PluginConfig(Boolean.parseBoolean(p.getProperty("autotransfer", String.valueOf(d.autoTransfer))), transferHost, transferPort, c1, c2, boundedInt(p.getProperty("check_interval_seconds"), d.checkIntervalSeconds, 1, Integer.MAX_VALUE, "check_interval_seconds", logger), boundedInt(p.getProperty("connect_timeout_ms"), d.connectTimeoutMs, 100, Integer.MAX_VALUE, "connect_timeout_ms", logger), boundedInt(p.getProperty("read_timeout_ms"), d.readTimeoutMs, 100, Integer.MAX_VALUE, "read_timeout_ms", logger), boundedInt(p.getProperty("retry_cooldown_seconds"), d.retryCooldownSeconds, 0, Integer.MAX_VALUE, "retry_cooldown_seconds", logger), boundedInt(p.getProperty("join_delay_ms"), d.joinDelayMs, 0, Integer.MAX_VALUE, "join_delay_ms", logger), Boolean.parseBoolean(p.getProperty("notify_players_when_target_down", String.valueOf(d.notifyPlayersWhenTargetDown))), p.getProperty("target_down_message", d.targetDownMessage), Boolean.parseBoolean(p.getProperty("debug", String.valueOf(d.debug))), intOrDefault(p.getProperty("minecraft_protocol_version"), d.minecraftProtocolVersion)); }
  }

  private static String readHostProperty(Properties p, String key, String def) { String value = p.getProperty(key); return value == null ? def : value.trim(); }
  private static int readPortProperty(Properties p, String key, int def, Logger logger) { return boundedInt(p.getProperty(key), def, 1, 65535, key, logger); }
  private static int boundedInt(String value, int def, int min, int max, String key, Logger logger) { int parsed = intOrDefault(value, def); if (parsed < min || parsed > max) { logger.warn("[PotatoAutoTransfer] Invalid {}. Using default {}.", key, def); return def; } return parsed; }
  private static int intOrDefault(String value, int def){ try { return Integer.parseInt(value.trim()); } catch(Exception e){ return def; } }

  private static String buildDefaultConfig() { return """
autotransfer=true

# Ziel, zu dem Spieler transferiert werden.
# Muss vom Spieler-Client erreichbar sein.
transfer_host=CHANGE_ME
transfer_port=25565

# Check 1: z. B. Main-Velocity oder Main-Proxy.
# Muss nur vom Fallback-Server erreichbar sein.
check1_enabled=true
check1_name=Main Velocity
check1_host=CHANGE_ME
check1_port=25565
check1_mode=minecraft_status

# Check 2: z. B. Paper-Mainserver.
# Muss nur vom Fallback-Server erreichbar sein.
check2_enabled=false
check2_name=Paper Main
check2_host=CHANGE_ME
check2_port=25565
check2_mode=minecraft_status

check_interval_seconds=5
connect_timeout_ms=1000
read_timeout_ms=1500
retry_cooldown_seconds=15
join_delay_ms=500
notify_players_when_target_down=false
target_down_message=Mainserver ist aktuell noch nicht vollständig erreichbar. Bitte warte kurz.
minecraft_protocol_version=-1
debug=false
"""; }

  private final class TransferCommand implements SimpleCommand {
    @Override public void execute(Invocation invocation) { CommandSource src = invocation.source(); String[] args = invocation.arguments(); if (args.length == 0) { if (!has(src, "potatoautotransfer.transfer")) return; int planned=0; for(Player p:proxy.getAllPlayers()) if(transferIfEligible(p,config.get())) planned++; src.sendMessage(Component.text("Geplante Transfers: "+planned)); return; } switch (args[0].toLowerCase()) { case "status" -> status(src); case "reload" -> reload(src); case "on", "off" -> toggle(src, args[0].equalsIgnoreCase("on")); case "help" -> src.sendMessage(Component.text("/transfer [status|reload|on|off|help]")); default -> src.sendMessage(Component.text("/transfer help")); } }
    private void status(CommandSource src) { if (!has(src, "potatoautotransfer.status")) return; PluginConfig cfg=config.get(); ReachabilityState s=reachability.get(); long baseAge=s.checkedAtMs==0?-1:(System.currentTimeMillis()-s.checkedAtMs); src.sendMessage(Component.text("PotatoAutoTransfer:")); src.sendMessage(Component.text("autotransfer="+cfg.autoTransfer)); src.sendMessage(Component.text("transfer="+cfg.transferHost+":"+cfg.transferPort)); src.sendMessage(Component.text("all_checks_reachable="+s.allRequiredChecksReachable)); for (CheckState c : s.checks) { long age=c.checkedAtMs==0?-1:(System.currentTimeMillis()-c.checkedAtMs); src.sendMessage(Component.text("")); src.sendMessage(Component.text(c.id+":")); src.sendMessage(Component.text("  enabled="+c.enabled)); if (c.enabled) { src.sendMessage(Component.text("  name="+c.name)); src.sendMessage(Component.text("  target="+c.host+":"+c.port)); src.sendMessage(Component.text("  mode="+c.mode)); src.sendMessage(Component.text("  reachable="+c.reachable)); src.sendMessage(Component.text("  detail="+c.detail)); src.sendMessage(Component.text("  ageMs="+age)); } else { src.sendMessage(Component.text("  detail=disabled")); } } src.sendMessage(Component.text("")); src.sendMessage(Component.text("checkedAgeMs="+baseAge)); }
    private void reload(CommandSource src) { if (!has(src, "potatoautotransfer.admin")) return; try { reloadConfigInternal(); performReachabilityCheck(true); src.sendMessage(Component.text("Config reloaded.")); } catch (Exception e) { src.sendMessage(Component.text("Reload failed: "+e.getMessage())); } }
    private void toggle(CommandSource src, boolean enabled) { if (!has(src, "potatoautotransfer.admin")) return; try { setAutoTransferAndSave(enabled); src.sendMessage(Component.text("autotransfer="+enabled)); } catch (Exception e) { src.sendMessage(Component.text("Save failed: "+e.getMessage())); } }
    @Override public boolean hasPermission(Invocation invocation) { String[] a=invocation.arguments(); CommandSource s=invocation.source(); if (s instanceof ConsoleCommandSource) return true; if (a.length==0) return s.hasPermission("potatoautotransfer.transfer"); return switch(a[0].toLowerCase()){ case "status" -> s.hasPermission("potatoautotransfer.status"); case "reload","on","off" -> s.hasPermission("potatoautotransfer.admin"); default -> true;}; }
    private boolean has(CommandSource src, String perm){ if (src instanceof ConsoleCommandSource || src.hasPermission(perm)) return true; src.sendMessage(Component.text("No permission: "+perm)); return false; }
  }
}
