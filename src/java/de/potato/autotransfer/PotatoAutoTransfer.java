package de.potato.autotransfer;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.command.SimpleCommand.Invocation;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

@Plugin(
   id = "potatoautotransfer",
   name = "PotatoAutoTransfer",
   version = "1.0.0",
   description = "Auto/Manual transfer all players from this Velocity proxy to another host:port."
)
public final class PotatoAutoTransfer {
   private static final String CONFIG_FILE = "config.properties";
   private static final String DEFAULT_CONFIG = "# PotatoAutoTransfer config\n# autotransfer=true: automatisch transferieren, sobald Ziel erreichbar ist\n# autotransfer=false: nur per /transfer\nautotransfer=true\ntarget_host=217.154.212.182\ntarget_port=25566\n\n# wie oft Reachability geprüft wird\ncheck_interval_seconds=5\n\n# TCP connect timeout für Reachability-Check\ntcp_timeout_ms=700\n\n# Cooldown pro Spieler, falls Transfer fehlschlägt\nretry_cooldown_seconds=15\n\n# Delay nach Login bevor transferToHost ausgelöst wird\njoin_delay_ms=250\n";
   private final ProxyServer proxy;
   private final Logger logger;
   private final Path dataDir;
   private volatile boolean autoTransfer = true;
   private volatile String targetHost = "217.154.212.182";
   private volatile int targetPort = 25566;
   private volatile int checkIntervalSeconds = 5;
   private volatile int tcpTimeoutMs = 700;
   private volatile int retryCooldownSeconds = 15;
   private volatile int joinDelayMs = 250;
   private volatile boolean lastReachable = false;
   private volatile long lastReachableCheckMs = 0L;
   private final ConcurrentHashMap<UUID, Long> lastAttemptMs = new ConcurrentHashMap<>();
   private ScheduledTask repeatingTask;

   @Inject
   public PotatoAutoTransfer(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
      this.proxy = Objects.requireNonNull(proxy, "proxy");
      this.logger = Objects.requireNonNull(logger, "logger");
      this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
   }

   @Subscribe
   public void onInit(ProxyInitializeEvent event) {
      try {
         this.ensureDefaultConfigExists();
         this.reloadConfigInternal();
      } catch (Exception e) {
         this.logger.error("[PotatoAutoTransfer] Failed to create/load config. Using built-in defaults.", e);
      }

      this.registerCommands();
      this.startScheduler();
      this.logger
         .info(
            "[PotatoAutoTransfer] Loaded. autotransfer={}, target={}:{}, check={}s",
            new Object[]{this.autoTransfer, this.targetHost, this.targetPort, this.checkIntervalSeconds}
         );
   }

   @Subscribe
   public void onPostLogin(PostLoginEvent event) {
      if (this.autoTransfer) {
         Player player = event.getPlayer();
         if (this.isTargetReachableCached()) {
            this.scheduleTransferPlayer(player, this.joinDelayMs);
         }
      }
   }

   private void registerCommands() {
      SimpleCommand cmd = new TransferCommand();
      boolean registeredTransfer = this.tryRegisterCommand("transfer", cmd, "autotransfer");
      if (!registeredTransfer) {
         this.tryRegisterCommand("potatotransfer", cmd, "autotransfer");
      }
   }

   private boolean tryRegisterCommand(String primary, SimpleCommand cmd, String... aliases) {
      try {
         CommandMeta meta = this.proxy.getCommandManager().metaBuilder(primary).plugin(this).aliases(aliases).build();
         this.proxy.getCommandManager().register(meta, cmd);
         this.logger.info("[PotatoAutoTransfer] Command registered: /{}", primary);
         return true;
      } catch (IllegalArgumentException ex) {
         this.logger.warn("[PotatoAutoTransfer] Could not register /{} (probably already registered).", primary);
         return false;
      } catch (Exception ex) {
         this.logger.error("[PotatoAutoTransfer] Failed to register /{}", primary, ex);
         return false;
      }
   }

   private void startScheduler() {
      if (this.repeatingTask != null) {
         try {
            this.repeatingTask.cancel();
         } catch (Exception var2) {
         }
      }

      this.repeatingTask = this.proxy.getScheduler().buildTask(this, this::tick).repeat(this.checkIntervalSeconds, TimeUnit.SECONDS).schedule();
   }

   private void tick() {
      boolean reachableNow = this.checkTcpReachable(this.targetHost, this.targetPort, this.tcpTimeoutMs);
      this.lastReachable = reachableNow;
      this.lastReachableCheckMs = System.currentTimeMillis();
      if (this.autoTransfer) {
         if (reachableNow) {
            this.transferAllWithCooldown();
         }
      }
   }

   private boolean checkTcpReachable(String host, int port, int timeoutMs) {
      if (host != null && !host.isBlank() && port >= 1 && port <= 65535) {
         try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), Math.max(100, timeoutMs));
            return true;
         } catch (IOException ignored) {
            return false;
         }
      } else {
         return false;
      }
   }

   private boolean isTargetReachableCached() {
      long ageMs = System.currentTimeMillis() - this.lastReachableCheckMs;
      if (ageMs > this.checkIntervalSeconds * 2000L) {
         boolean reachableNow = this.checkTcpReachable(this.targetHost, this.targetPort, this.tcpTimeoutMs);
         this.lastReachable = reachableNow;
         this.lastReachableCheckMs = System.currentTimeMillis();
      }

      return this.lastReachable;
   }

   private void scheduleTransferPlayer(Player player, int delayMs) {
      if (player != null) {
         this.proxy.getScheduler().buildTask(this, () -> {
            if (player.isActive()) {
               if (this.isTargetReachableCached()) {
                  if (this.isCooldownOver(player.getUniqueId())) {
                     try {
                        player.transferToHost(InetSocketAddress.createUnresolved(this.targetHost, this.targetPort));
                        this.markAttempt(player.getUniqueId());
                     } catch (IllegalArgumentException ex) {
                        this.logger.debug("[PotatoAutoTransfer] Transfer failed for {}: {}", player.getUsername(), ex.getMessage());
                        this.markAttempt(player.getUniqueId());
                     } catch (Exception ex) {
                        this.logger.debug("[PotatoAutoTransfer] Transfer exception for {}", player.getUsername(), ex);
                        this.markAttempt(player.getUniqueId());
                     }
                  }
               }
            }
         }).delay(Math.max(0, delayMs), TimeUnit.MILLISECONDS).schedule();
      }
   }

   private boolean isCooldownOver(UUID uuid) {
      long now = System.currentTimeMillis();
      long last = this.lastAttemptMs.getOrDefault(uuid, 0L);
      return now - last >= this.retryCooldownSeconds * 1000L;
   }

   private void markAttempt(UUID uuid) {
      this.lastAttemptMs.put(uuid, System.currentTimeMillis());
   }

   private void transferAllWithCooldown() {
      Collection<Player> players = this.proxy.getAllPlayers();
      if (!players.isEmpty()) {
         for (Player player : players) {
            if (player != null && player.isActive() && this.isCooldownOver(player.getUniqueId())) {
               this.scheduleTransferPlayer(player, 0);
            }
         }
      }
   }

   private Path configPath() {
      return this.dataDir.resolve("config.properties");
   }

   private void ensureDefaultConfigExists() throws IOException {
      Files.createDirectories(this.dataDir);
      Path cfg = this.configPath();
      if (!Files.exists(cfg)) {
         Files.writeString(
            cfg,
            "# PotatoAutoTransfer config\n# autotransfer=true: automatisch transferieren, sobald Ziel erreichbar ist\n# autotransfer=false: nur per /transfer\nautotransfer=true\ntarget_host=217.154.212.182\ntarget_port=25566\n\n# wie oft Reachability geprüft wird\ncheck_interval_seconds=5\n\n# TCP connect timeout für Reachability-Check\ntcp_timeout_ms=700\n\n# Cooldown pro Spieler, falls Transfer fehlschlägt\nretry_cooldown_seconds=15\n\n# Delay nach Login bevor transferToHost ausgelöst wird\njoin_delay_ms=250\n",
            StandardCharsets.UTF_8
         );
      }
   }

   private synchronized void reloadConfigInternal() throws IOException {
      Properties p = new Properties();
      Path cfg = this.configPath();

      try (BufferedReader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
         p.load(r);
      }

      boolean newAuto = Boolean.parseBoolean(p.getProperty("autotransfer", String.valueOf(this.autoTransfer)));
      String newHost = p.getProperty("target_host", this.targetHost);
      if (newHost != null) {
         newHost = newHost.trim();
      }

      if (newHost == null || newHost.isBlank()) {
         newHost = this.targetHost;
      }

      int newPort = parseIntSafe(p.getProperty("target_port"), this.targetPort);
      if (newPort < 1 || newPort > 65535) {
         newPort = this.targetPort;
      }

      int newInterval = parseIntSafe(p.getProperty("check_interval_seconds"), this.checkIntervalSeconds);
      if (newInterval < 1) {
         newInterval = 5;
      }

      int newTimeout = parseIntSafe(p.getProperty("tcp_timeout_ms"), this.tcpTimeoutMs);
      if (newTimeout < 50) {
         newTimeout = 700;
      }

      int newCooldown = parseIntSafe(p.getProperty("retry_cooldown_seconds"), this.retryCooldownSeconds);
      if (newCooldown < 0) {
         newCooldown = 15;
      }

      int newJoinDelay = parseIntSafe(p.getProperty("join_delay_ms"), this.joinDelayMs);
      if (newJoinDelay < 0) {
         newJoinDelay = 250;
      }

      this.autoTransfer = newAuto;
      this.targetHost = newHost;
      this.targetPort = newPort;
      this.checkIntervalSeconds = newInterval;
      this.tcpTimeoutMs = newTimeout;
      this.retryCooldownSeconds = newCooldown;
      this.joinDelayMs = newJoinDelay;
      this.startScheduler();
   }

   private static int parseIntSafe(String s, int def) {
      if (s == null) {
         return def;
      }

      try {
         return Integer.parseInt(s.trim());
      } catch (Exception ignored) {
         return def;
      }
   }

   private synchronized void setAutoTransferAndSave(boolean enabled) throws IOException {
      Properties p = new Properties();
      Path cfg = this.configPath();

      try (BufferedReader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
         p.load(r);
      }

      p.setProperty("autotransfer", String.valueOf(enabled));

      try (BufferedWriter w = Files.newBufferedWriter(cfg, StandardCharsets.UTF_8)) {
         p.store(w, "PotatoAutoTransfer config");
      }

      this.autoTransfer = enabled;
   }

   private final class TransferCommand implements SimpleCommand {
      public void execute(Invocation invocation) {
         CommandSource src = invocation.source();
         String[] args = (String[])invocation.arguments();
         if (args.length == 0) {
            if (!this.isConsole(src) && !src.hasPermission("potatoautotransfer.transfer")) {
               src.sendMessage(Component.text("No permission: potatoautotransfer.transfer"));
            } else if (!PotatoAutoTransfer.this.isTargetReachableCached()) {
               src.sendMessage(Component.text("Target NOT reachable: " + PotatoAutoTransfer.this.targetHost + ":" + PotatoAutoTransfer.this.targetPort));
            } else {
               int count = PotatoAutoTransfer.this.proxy.getAllPlayers().size();
               PotatoAutoTransfer.this.transferAllWithCooldown();
               src.sendMessage(
                  Component.text(
                     "Transfer started for " + count + " player(s) -> " + PotatoAutoTransfer.this.targetHost + ":" + PotatoAutoTransfer.this.targetPort
                  )
               );
            }
         } else {
            String sub = args[0].toLowerCase();
            switch (sub) {
               case "status":
                  if (!this.isConsole(src) && !src.hasPermission("potatoautotransfer.status")) {
                     src.sendMessage(Component.text("No permission: potatoautotransfer.status"));
                     return;
                  }

                  src.sendMessage(
                     Component.text(
                        "PotatoAutoTransfer: autotransfer="
                           + PotatoAutoTransfer.this.autoTransfer
                           + " target="
                           + PotatoAutoTransfer.this.targetHost
                           + ":"
                           + PotatoAutoTransfer.this.targetPort
                           + " reachable="
                           + PotatoAutoTransfer.this.isTargetReachableCached()
                           + " interval="
                           + PotatoAutoTransfer.this.checkIntervalSeconds
                           + "s"
                     )
                  );
                  break;
               case "reload":
                  if (!this.isConsole(src) && !src.hasPermission("potatoautotransfer.admin")) {
                     src.sendMessage(Component.text("No permission: potatoautotransfer.admin"));
                     return;
                  }

                  try {
                     PotatoAutoTransfer.this.reloadConfigInternal();
                     src.sendMessage(Component.text("Config reloaded."));
                  } catch (Exception e) {
                     src.sendMessage(Component.text("Config reload failed: " + e.getMessage()));
                     PotatoAutoTransfer.this.logger.error("[PotatoAutoTransfer] Config reload failed", e);
                  }
                  break;
               case "on":
               case "off":
                  if (!this.isConsole(src) && !src.hasPermission("potatoautotransfer.admin")) {
                     src.sendMessage(Component.text("No permission: potatoautotransfer.admin"));
                     return;
                  }

                  boolean enabled = sub.equals("on");

                  try {
                     PotatoAutoTransfer.this.setAutoTransferAndSave(enabled);
                     PotatoAutoTransfer.this.startScheduler();
                     src.sendMessage(Component.text("autotransfer set to " + enabled));
                  } catch (Exception e) {
                     src.sendMessage(Component.text("Failed to save: " + e.getMessage()));
                     PotatoAutoTransfer.this.logger.error("[PotatoAutoTransfer] Failed to save autotransfer flag", e);
                  }
                  break;
               default:
                  src.sendMessage(Component.text("Usage: /transfer [status|reload|on|off]\nNo args = transfer all players to configured target."));
            }
         }
      }

      public boolean hasPermission(Invocation invocation) {
         return true;
      }

      private boolean isConsole(Object src) {
         return src instanceof ConsoleCommandSource;
      }
   }
}
