# PotatoAutoTransfer (Deutsch)

## Überblick
PotatoAutoTransfer läuft auf einem Fallback-/Warteraum-Server hinter Velocity und transferiert Spieler automatisch zurück zum Main-Server, sobald dieser wieder erreichbar ist.

## Architektur
Spieler verbinden sich zuerst mit einem öffentlichen Python-Failover-Proxy.

`Spieler -> öffentlicher Python-Failover-Proxy -> MAIN oder FALLBACK`

- Wenn MAIN online ist, leitet der Python-Failover-Proxy neue Verbindungen direkt zu MAIN weiter.
- Wenn MAIN offline ist, leitet der Python-Failover-Proxy zu FALLBACK/Warteraum weiter.
- PotatoAutoTransfer läuft auf FALLBACK/Warteraum.
- PotatoAutoTransfer prüft regelmäßig `check_host`/`check_port`.
- Sobald `check_host` erreichbar ist, transferiert PotatoAutoTransfer Spieler zu `transfer_host`/`transfer_port` über `player.transferToHost(...)`.
- `transfer_host` muss vom Spieler-Client erreichbar sein.
- `check_host` muss nur vom Server erreichbar sein, auf dem PotatoAutoTransfer läuft.

## Konfiguration
Beispiel für `plugins/PotatoAutoTransfer/config.properties`:

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

### Konfigurations-Erklärung
- `autotransfer=true`: Spieler werden automatisch transferiert, sobald MAIN erreichbar ist.
- `transfer_host`/`transfer_port`: Ziel, zu dem der Client per `player.transferToHost(...)` geschickt wird. Muss öffentlich/vom Spieler erreichbar sein.
- `check_host`/`check_port`: Ziel, das vom Plugin geprüft wird, um zu erkennen, ob MAIN wieder online ist. Kann intern, LAN oder Tailscale sein.
- `check_mode=tcp`: Prüft nur offenen TCP-Port.
- `check_mode=minecraft_status`: Echter Minecraft Status-Ping.
- `minecraft_protocol_version=-1`: Default-/Auto-Wert für den Status-Ping.

## Commands
- `/transfer`: Manueller Transfer aller berechtigten Spieler, wenn `check_host` erreichbar ist.
- `/transfer status`: Zeigt AutoTransfer, Check-Ziel, Transfer-Ziel, Check-Modus, Reachability und Alter des letzten Checks.
- `/transfer reload`: Lädt die Config neu.
- `/transfer on`: Aktiviert Autotransfer und speichert es in der Config.
- `/transfer off`: Deaktiviert Autotransfer und speichert es in der Config.
- `/transfer help`: Zeigt Hilfe.

## Permissions
- `potatoautotransfer.transfer`
- `potatoautotransfer.status`
- `potatoautotransfer.admin`

## Installation
Build:

```bash
gradle build
```

Jar:

```text
build/libs/PotatoAutoTransfer-1.2.0.jar
```

Installation:
1. Jar nach `plugins/` kopieren.
2. Velocity starten.
3. `plugins/PotatoAutoTransfer/config.properties` bearbeiten.
4. `transfer_host`, `transfer_port`, `check_host`, `check_port` setzen.
5. `/transfer reload` ausführen oder Velocity neu starten.
6. `/transfer status` prüfen.

Wichtig:
Dieses Repository nutzt bewusst keinen Gradle Wrapper, weil in der Codex-Umgebung keine Binärdateien hinzugefügt werden sollen. GitHub Actions nutzt `gradle/actions/setup-gradle`.

## Troubleshooting
- `transfer_host=CHANGE_ME`: Kein Transfer.
- `check_host=CHANGE_ME`: Kein Transfer.
- `check_host` erreichbar, aber Transfer klappt nicht: Dann ist wahrscheinlich `transfer_host` vom Spieler-Client nicht erreichbar.
- `minecraft_status` geht nicht, aber TCP geht: Testweise `check_mode=tcp` setzen oder `minecraft_protocol_version` passend setzen.
- Spieler landen wieder im Fallback: Dann leitet der öffentliche Python-Failover-Proxy wahrscheinlich noch zum Fallback, weil MAIN aus Sicht des Python-Proxys noch offline ist.
- Tailscale-IP als `transfer_host` funktioniert nur, wenn der Spieler selbst im Tailscale-Netz ist.
- Tailscale-IP als `check_host` ist okay, wenn der Fallback-Server diese IP erreichen kann.

## Plugin-Metadaten
Die Velocity-Plugin-Metadaten (`velocity-plugin.json`) werden beim Build aus der `@Plugin`-Annotation über den Velocity-Annotation-Processor erzeugt. Es gibt keine manuelle `src/main/resources/velocity-plugin.json`.
