# NoChance

Spigot anti-cheat plugin for Minecraft 1.21+.

## What it does

37 detection checks across movement, combat, block interaction, damage, and player behaviour, validated through a 4-layer cascade (packet → physics → behaviour → ensemble). Includes replay capture, ML data export for review, and an optional web dashboard bridge.

## Server Requirements

- Spigot/Paper 1.21
- Optional: ViaVersion / ProtocolSupport for legacy clients, Floodgate / Geyser for Bedrock

## Build

```
mvn clean package
```

Output: `target/NoChance-<version>.jar`. Drop it into `plugins/`.

## Commands

`/nochance` (aliases: `/nc`, `/anticheat`, `/ac`)

Subcommands: `menu`, `reload`, `info`, `violations`, `reset`, `toggle`, `stats`, `alerts`, `review`, `export-ml`.

## Permissions

- `nochance.admin` - full access
- `nochance.alerts` - receive staff notifications
- `nochance.bypass.*` - granular bypass per check
- `nochance.staff.spectate` / `freeze` / `verbose`
- `nochance.review` - ML review GUI
- `nochance.web` - web dashboard pairing

Full list in `plugin.yml`.

## Detection categories

- Movement (12): Fly, Speed, NoClip, Phase, Step, Timer, Strider, BoatFly, ElytraFly, GroundSpoof, Jesus, Spider
- Combat (5): KillAura, AimAssist, AutoClicker, Reach, CombatGeometry
- Block (7): FastBreak, FastPlace, Nuker, Scaffold, AutoMine, AutoTool, XRay
- Damage (3): NoFall, Velocity, Criticals
- Player (7): Inventory, NoSlow, Blink, Disabler, AutoFish, Interact, Protocol

## Storage

SQLite by default. MySQL supported via `database.type: mysql` in `config.yml`. Connection pooling via HikariCP.

## Languages

`en`, `es`, `pt_BR`, `zh_CN`, `ru`. Set via `language` key.

## Disclaimer

This project is shared as source for review and personal use. **Do not redistribute, resell, or claim authorship.** Do not use the code, assets, or detection logic in another project without permission. Forks for personal study are fine; public mirrors, paid resources, or rebrands are not.

The plugin processes player movement and combat data to flag likely cheaters. It is not infallible - false positives can occur. Test on a staging server before deploying to production. The author takes no responsibility for bans issued by automated punishment, server downtime, or data loss.
