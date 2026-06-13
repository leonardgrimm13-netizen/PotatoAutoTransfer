# PotatoAutoTransfer (English)

## Overview
PotatoAutoTransfer runs on your fallback/waiting-room Velocity instance and transfers players back only when all required checks are online.

## Architecture
Players connect through a public Python failover proxy.

`Player -> public Python failover proxy -> Velocity/Main-Proxy -> Paper/Main`

- If main is down, players land on fallback/waiting room.
- PotatoAutoTransfer runs on fallback/waiting room.
- The plugin checks up to two independent targets:
  - `check1`: usually Main Velocity / Main Proxy
  - `check2`: usually Paper Main / Backend
- Transfer only happens when all enabled checks are reachable.
- Players are then transferred to `transfer_host`/`transfer_port`.

Important separation:
- `transfer_host` must be client-reachable public address.
- `check1_host` / `check2_host` can be internal/LAN/Tailscale because checks run from fallback server only.

## Example setup
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

Explanation:
- `transfer_host` must be reachable by players.
- `check1_host` and `check2_host` only need to be reachable from fallback.
- Tailscale IP is fine for check hosts.
- Tailscale IP as `transfer_host` only works if players are in Tailscale too.

## Configuration
```properties
autotransfer=true

# Target where players are transferred. Must be client reachable.
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

### Full config reference
- `autotransfer`: automatic transfer switch.
- `transfer_host` / `transfer_port`: destination for `player.transferToHost(...)`.
- `check1_enabled`, `check1_name`, `check1_host`, `check1_port`, `check1_mode`: first required check.
- `check2_enabled`, `check2_name`, `check2_host`, `check2_port`, `check2_mode`: optional second check.
- `check_interval_seconds`: periodic check interval.
- `connect_timeout_ms` / `read_timeout_ms`: socket timeouts.
- `retry_cooldown_seconds`: per-player retry cooldown.
- `join_delay_ms`: delay after login before auto transfer attempt.
- `notify_players_when_target_down`: send message while blocked.
- `target_down_message`: message text.
- `minecraft_protocol_version`: protocol for status ping (`-1` default/auto behavior).
- `debug`: detailed logs.

Legacy compatibility:
- Old keys `check_host`, `check_port`, `check_mode` are still accepted as fallback for check1.
- Migrate to `check1_*` keys; plugin logs a one-time warning.

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
- Players are not transferred:
  - Check `/transfer status`.
  - If both checks are enabled, both must be online.
- check1 online, check2 offline:
  - Transfer is correctly blocked.
  - Paper/Main backend is not ready yet.
- `check2_enabled=true` and `check2_host=CHANGE_ME`:
  - Transfer is blocked.
- Players return to fallback:
  - Public failover proxy still sees main as offline.
- Tailscale IP as `transfer_host` fails for regular players:
  - Expected unless players are in Tailscale.
- `minecraft_status` fails but TCP works:
  - Try `check1_mode=tcp` or `check2_mode=tcp`.
  - Or set matching `minecraft_protocol_version`.
