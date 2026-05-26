# PotatoAutoTransfer (English)

## Overview
PotatoAutoTransfer runs on a fallback/waiting-room server behind Velocity and automatically transfers players back to the main server as soon as it is reachable again.

## Architecture
Players first connect to a public Python failover proxy.

`Player -> public Python failover proxy -> MAIN or FALLBACK`

- If MAIN is online, the Python failover proxy forwards new connections directly to MAIN.
- If MAIN is offline, the Python failover proxy forwards to FALLBACK/waiting room.
- PotatoAutoTransfer runs on FALLBACK/waiting room.
- PotatoAutoTransfer regularly checks `check_host`/`check_port`.
- As soon as `check_host` is reachable, PotatoAutoTransfer transfers players to `transfer_host`/`transfer_port` via `player.transferToHost(...)`.
- `transfer_host` must be reachable by the player client.
- `check_host` only needs to be reachable by the server where PotatoAutoTransfer runs.

## Configuration
Example for `plugins/PotatoAutoTransfer/config.properties`:

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

### Configuration explanation
- `autotransfer=true`: Players are transferred automatically as soon as MAIN is reachable.
- `transfer_host`/`transfer_port`: Target the client is sent to via `player.transferToHost(...)`. Must be public/reachable by players.
- `check_host`/`check_port`: Target checked by the plugin to detect whether MAIN is online again. Can be internal, LAN, or Tailscale.
- `check_mode=tcp`: Checks only whether the TCP port is open.
- `check_mode=minecraft_status`: Real Minecraft status ping.
- `minecraft_protocol_version=-1`: Default/auto value for the status ping.

## Commands
- `/transfer`: Manual transfer of all permitted players when `check_host` is reachable.
- `/transfer status`: Shows AutoTransfer, check target, transfer target, check mode, reachability, and age of the last check.
- `/transfer reload`: Reloads the config.
- `/transfer on`: Enables autotransfer and persists it in the config.
- `/transfer off`: Disables autotransfer and persists it in the config.
- `/transfer help`: Shows help.

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
1. Copy the jar to `plugins/`.
2. Start Velocity.
3. Edit `plugins/PotatoAutoTransfer/config.properties`.
4. Set `transfer_host`, `transfer_port`, `check_host`, `check_port`.
5. Run `/transfer reload` or restart Velocity.
6. Check `/transfer status`.

Important:
This repository intentionally does not use a Gradle Wrapper because no binary files should be added in the Codex environment. GitHub Actions uses `gradle/actions/setup-gradle`.

## Troubleshooting
- `transfer_host=CHANGE_ME`: No transfer.
- `check_host=CHANGE_ME`: No transfer.
- `check_host` reachable, but transfer fails: Then `transfer_host` is most likely not reachable by the player client.
- `minecraft_status` fails, but TCP works: Try `check_mode=tcp` or set a matching `minecraft_protocol_version`.
- Players land in fallback again: The public Python failover proxy is probably still routing to fallback because MAIN is still offline from the proxy perspective.
- A Tailscale IP as `transfer_host` only works if the player is also in the Tailscale network.
- A Tailscale IP as `check_host` is fine if the fallback server can reach that IP.

## Plugin metadata
Velocity plugin metadata (`velocity-plugin.json`) is generated during build from the `@Plugin` annotation via the Velocity annotation processor. There is no manual `src/main/resources/velocity-plugin.json`.
