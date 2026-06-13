# PotatoAutoTransfer (Deutsch)

## Überblick
PotatoAutoTransfer läuft auf deiner Fallback-/Warteraum-Velocity-Instanz und transferiert Spieler nur dann zurück, wenn alle benötigten Checks online sind.

## Architektur
Spieler verbinden sich über einen öffentlichen Python-Failover-Proxy.

`Spieler -> öffentlicher Python-Failover-Proxy -> Velocity/Main-Proxy -> Paper/Main`

- Wenn Main offline ist, landen Spieler im Fallback/Warteraum.
- PotatoAutoTransfer läuft im Fallback/Warteraum.
- Das Plugin prüft bis zu zwei getrennte Ziele:
  - `check1`: typischerweise Main Velocity / Main Proxy
  - `check2`: typischerweise Paper Main / Backend
- Transfer passiert nur, wenn alle aktivierten Checks erreichbar sind.
- Danach werden Spieler zu `transfer_host`/`transfer_port` transferiert.

Wichtige Trennung:
- `transfer_host` muss als öffentliche Adresse vom Client erreichbar sein.
- `check1_host` / `check2_host` dürfen intern/LAN/Tailscale sein, weil Checks nur vom Fallback-Server laufen.

## Beispiel für dein Setup
```properties
transfer_host=play.example.com
transfer_port=25565

check1_enabled=true
check1_name=Main Velocity
check1_host=100.64.0.10
check1_port=25565
check1_mode=minecraft_status

check2_enabled=true
check2_name=Paper Main
check2_host=100.64.0.11
check2_port=25565
check2_mode=minecraft_status
```

Erklärung:
- `transfer_host` muss vom Spieler erreichbar sein.
- `check1_host` und `check2_host` müssen nur vom Fallback-Server erreichbar sein.
- Tailscale-IP ist als Check-Host okay.
- Tailscale-IP als `transfer_host` funktioniert nur, wenn Spieler ebenfalls im Tailscale-Netz sind.

## Konfiguration
```properties
autotransfer=true

# Ziel, zu dem Spieler transferiert werden. Muss vom Client erreichbar sein.
transfer_host=CHANGE_ME
transfer_port=25565

# Check 1 (Main Velocity/Main Proxy)
check1_enabled=true
check1_name=Main Velocity
check1_host=CHANGE_ME
check1_port=25565
check1_mode=minecraft_status

# Check 2 (Paper Main/Backend)
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
```

### Vollständige Konfigurations-Erklärung
- `autotransfer`: Schalter für automatischen Transfer.
- `transfer_host` / `transfer_port`: Ziel für `player.transferToHost(...)`.
- `check1_enabled`, `check1_name`, `check1_host`, `check1_port`, `check1_mode`: erster Pflicht-Check.
- `check2_enabled`, `check2_name`, `check2_host`, `check2_port`, `check2_mode`: optionaler zweiter Check.
- `check_interval_seconds`: Intervall für Reachability-Prüfung.
- `connect_timeout_ms` / `read_timeout_ms`: Socket-Timeouts.
- `retry_cooldown_seconds`: Cooldown pro Spieler für erneute Versuche.
- `join_delay_ms`: Verzögerung nach Login vor Auto-Transfer-Versuch.
- `notify_players_when_target_down`: Nachricht senden, wenn blockiert.
- `target_down_message`: Nachrichtentext.
- `minecraft_protocol_version`: Protokoll für Status-Ping (`-1` default/auto).
- `debug`: detaillierte Logs.

Legacy-Kompatibilität:
- Alte Keys `check_host`, `check_port`, `check_mode` werden weiter als Fallback für check1 unterstützt.
- Bitte auf `check1_*` migrieren; das Plugin loggt eine einmalige Warnung.

## Commands
- `/transfer`
- `/transfer status`
- `/transfer reload`
- `/transfer on`
- `/transfer off`
- `/transfer help`

## Permissions
- `potatoautotransfer.transfer`
- `potatoautotransfer.status`
- `potatoautotransfer.admin`

## Installation
```bash
gradle build
```

Jar:
```text
build/libs/PotatoAutoTransfer-1.3.0.jar
```

## Troubleshooting
- Spieler werden nicht transferiert:
  - `/transfer status` prüfen.
  - Wenn beide Checks aktiv sind, müssen beide online sein.
- check1 online, check2 offline:
  - Transfer wird korrekt blockiert.
  - Paper/Main-Backend ist noch nicht bereit.
- `check2_enabled=true` und `check2_host=CHANGE_ME`:
  - Transfer wird blockiert.
- Spieler landen wieder im Fallback:
  - Öffentlicher Failover-Proxy sieht Main vermutlich noch als offline.
- Tailscale-IP als `transfer_host` klappt nicht für normale Spieler:
  - Erwartet, außer Spieler sind ebenfalls im Tailscale-Netz.
- `minecraft_status` scheitert, TCP geht:
  - Testweise `check1_mode=tcp` oder `check2_mode=tcp` setzen.
  - Oder passende `minecraft_protocol_version` setzen.
