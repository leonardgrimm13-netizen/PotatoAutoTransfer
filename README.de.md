# PotatoAutoTransfer (Deutsch)

## Was macht das Plugin?
PotatoAutoTransfer transferiert Spieler von einem Velocity-Proxy zu einem **externen** Minecraft-/Velocity-Ziel per `host:port` über `player.transferToHost(...)`.

**Wichtig:** Das Ziel ist **nicht** in `velocity.toml` registriert und wird absichtlich nicht über `RegisteredServer` verbunden.

## Installation
1. Plugin bauen (`gradle build`)
2. Jar nach `plugins/` kopieren
3. Velocity starten
4. `plugins/PotatoAutoTransfer/config.properties` bearbeiten
5. `/transfer reload` ausführen (oder Proxy neu starten)

## Architektur-Beispiel
Spieler verbinden sich zu einem öffentlichen Minecraft-/Python-Failover-Proxy:

`Spieler -> öffentlicher Failover-Proxy -> MAIN/FALLBACK`

PotatoAutoTransfer läuft auf FALLBACK und transferiert zurück, sobald MAIN wieder erreichbar ist.

Beispiel:
```properties
transfer_host=play.example.com
transfer_port=25565
check_host=100.64.0.10
check_port=25565
```

- `transfer_host` muss vom Spieler erreichbar sein.
- `check_host` muss nur vom Fallback-Server erreichbar sein.

## Beispielconfig
```properties
autotransfer=true
transfer_host=CHANGE_ME
transfer_port=25565
check_host=CHANGE_ME
check_port=25565
check_mode=minecraft_status
check_interval_seconds=5
connect_timeout_ms=1000
read_timeout_ms=1500
retry_cooldown_seconds=15
join_delay_ms=500
notify_players_when_target_down=false
target_down_message=Mainserver ist aktuell noch nicht erreichbar. Bitte warte kurz.
minecraft_protocol_version=-1
debug=false
```

## Erklärung Config
- `autotransfer`: Bei `true` automatische Transfers (wenn Ziel erreichbar)
- `transfer_host`/`transfer_port`: Externes Ziel für `player.transferToHost(...)` (vom Spieler erreichbar)
- `check_host`/`check_port`: Ziel für Reachability-Checks (nur serverseitig erreichbar)
- `check_mode`: `tcp` oder `minecraft_status` (empfohlen)
- `check_interval_seconds`: Prüfintervall
- `connect_timeout_ms`/`read_timeout_ms`: Timeouts
- `retry_cooldown_seconds`: Cooldown pro Spieler nach Versuch
- `join_delay_ms`: Delay nach Login
- `notify_players_when_target_down`: Spielerhinweis bei Down-Ziel
- `target_down_message`: Nachricht für Spieler
- `minecraft_protocol_version`: Status-Ping Protocol-Version (`-1` default)
- `debug`: Detail-Logs

## Commands
- `/transfer` (manueller Transfer)
- `/transfer status`
- `/transfer reload`
- `/transfer on`
- `/transfer off`
- `/transfer help`

## Permissions
- `potatoautotransfer.transfer`
- `potatoautotransfer.status`
- `potatoautotransfer.admin`

## Plugin-Metadaten
Die Velocity-Plugin-Metadaten (`velocity-plugin.json`) werden beim Build aus der `@Plugin`-Annotation über den Velocity-Annotation-Processor erzeugt.

## Troubleshooting
- **`transfer_host=CHANGE_ME` oder `check_host=CHANGE_ME`**: Kein Transfer, Konsole warnt korrekt.
- **TCP geht, aber `minecraft_status` nicht**: Ziel antwortet nicht korrekt auf Status-Ping; testweise `check_mode=tcp` nutzen oder `minecraft_protocol_version` passend setzen.
- **Spieler werden nicht transferiert**: Reachability/Config prüfen, `status` aufrufen, Cooldown beachten.
- **Transfer nur mit modernen Clients/Velocity**: Nutze aktuelle Velocity-Versionen.

## check_mode Unterschiede
- `tcp`: prüft nur offenen Port.
- `minecraft_status`: echter Minecraft Status-Ping (Handshake + Status Request + Response).


## Hinweis
Dieses Repository nutzt bewusst **keinen Gradle Wrapper**, weil die Codex-/Ausführungsumgebung keine Binärdateien im Patch zulässt. GitHub Actions verwendet stattdessen `gradle/actions/setup-gradle` mit fester `gradle-version`.
