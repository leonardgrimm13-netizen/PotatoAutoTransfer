# PotatoAutoTransfer

Velocity plugin for automatic/manual transfer of players to an **external** target (`host:port`) using `player.transferToHost(...)`.

> The target is intentionally **not** a `RegisteredServer` and not part of `velocity.toml`.

## Build
```bash
gradle build
```

Result jar:
- `build/libs/PotatoAutoTransfer-1.1.0.jar`

## Quick setup
1. Copy jar to `plugins/`.
2. Start Velocity once.
3. Edit `plugins/PotatoAutoTransfer/config.properties`:
   - `transfer_host=play.example.com`
   - `transfer_port=25565`
   - `check_host=100.64.0.10`
   - `check_port=25565`
4. Run `/transfer reload`.

## Architecture example
Players connect to a public Minecraft/Python failover proxy:

`Players -> public failover proxy -> MAIN/FALLBACK`

PotatoAutoTransfer runs on the FALLBACK server and checks when MAIN is back online.

Example:

```properties
transfer_host=play.example.com
transfer_port=25565
check_host=100.64.0.10
check_port=25565
```

- `transfer_host` must be reachable by the player client.
- `check_host` only needs to be reachable by the fallback server.

## Commands
- `/transfer` manual transfer for eligible players
- `/transfer status`
- `/transfer reload`
- `/transfer on`
- `/transfer off`
- `/transfer help`

If `/transfer` is already taken, plugin falls back to `/potatotransfer` or `/autotransfer`.

## Permissions
- `potatoautotransfer.transfer`
- `potatoautotransfer.status`
- `potatoautotransfer.admin`

See `README.de.md` for full German docs.

## Plugin metadata
Velocity plugin metadata (`velocity-plugin.json`) is generated from the `@Plugin` annotation by the Velocity annotation processor during build.


## Note
This repository intentionally does **not** include a Gradle Wrapper because this Codex environment disallows adding binary files. CI uses `gradle/actions/setup-gradle` with an explicit Gradle version.
