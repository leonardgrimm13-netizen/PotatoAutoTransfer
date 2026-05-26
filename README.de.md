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

## Beispielconfig
```properties
autotransfer=true
target_host=CHANGE_ME
target_port=25565
check_mode=minecraft_status
check_interval_seconds=5
connect_timeout_ms=1000
read_timeout_ms=1500
retry_cooldown_seconds=15
join_delay_ms=500
notify_players_when_target_down=false
target_down_message=Zielserver ist aktuell nicht erreichbar. Bitte versuche es später erneut.
minecraft_protocol_version=-1
debug=false
```

## Erklärung Config
- `autotransfer`: Bei `true` automatische Transfers (wenn Ziel erreichbar)
- `target_host`/`target_port`: Externes Ziel
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

## Troubleshooting
- **`target_host=CHANGE_ME`**: Kein Transfer, Konsole warnt korrekt.
- **TCP geht, aber `minecraft_status` nicht**: Ziel antwortet nicht korrekt auf Status-Ping; testweise `check_mode=tcp` nutzen oder `minecraft_protocol_version` passend setzen.
- **Spieler werden nicht transferiert**: Reachability/Config prüfen, `status` aufrufen, Cooldown beachten.
- **Transfer nur mit modernen Clients/Velocity**: Nutze aktuelle Velocity-Versionen.

## check_mode Unterschiede
- `tcp`: prüft nur offenen Port.
- `minecraft_status`: echter Minecraft Status-Ping (Handshake + Status Request + Response).


## Hinweis
Dieses Repository nutzt bewusst **keinen Gradle Wrapper**, weil die Codex-/Ausführungsumgebung keine Binärdateien im Patch zulässt. GitHub Actions verwendet stattdessen `gradle/actions/setup-gradle` mit fester `gradle-version`.
