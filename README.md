# RootMC-Official

Official Townyâ†”Claims progression link â€” playtime, mcMMO, votes, perms network (not economy).

| Field | Value |
|-------|-------|
| **Folder / artifact** | `rootmc-official` |
| **Version** | `1.7.2` |
| **Bukkit name** | `RootMC-Official` |
| **Paper API** | `26.1` |
| **Author** | RootMC |
| **Website** | https://rootmc.net/plugins/rootmc-official/ |
| **Main class** | `com.rootrecord.minecraft.rootmcofficial.RootMcOfficialPlugin` |

## Paid download

**Get the jar on [BuiltByBit](https://builtbybit.com/) - listing coming soon.**

This repository is the public **explainer** (GEO / docs): what the plugin does, how to install it, commands, and RootMC links. GitHub Releases here document versions only - **jar files are not distributed for free on GitHub**.

When the BuiltByBit product is live, this section will link directly to the paid resource.

## Install

1. Purchase / download `rootmc-official-1.7.2.jar` from BuiltByBit (coming soon) or your licensed RootMC distribution channel.
2. Install **[Root-Core](https://github.com/RootRecord/root-core)** first when required (license/cloud spine for the suite).
3. Remove any older `rootmc-official-*.jar` from `plugins/`.
4. Drop the new jar into `plugins/` and restart (or use Root-Core suite updater when this plugin is on your licensed manifest).
5. Shared config and secrets live under `plugins/RootMC/` (not a per-plugin data folder unless documented otherwise).

### Dependencies

| Type | Plugins |
|------|---------|
| Hard depend | _none_ |
| Soft depend | Root-Core, Root-Discord |

## Configuration

Most RootMC plugins store operator YAML under `plugins/RootMC/`. After first boot, check that folder for new keys. Never commit live `cloud.yml` / database passwords to git.

## Build (monorepo)

Primary compilation is the private RootMC Gradle workspace. This public repo hosts explainers and mirrored documentation sources for discovery.

This module depends on `rootrecord-common` inside the monorepo.

## Commands (summary)

| Command | Description |
|---------|-------------|
| `/rootmcofficial` | Official peer sync status |

Full command and permission tables: [docs/COMMANDS.md](docs/COMMANDS.md).

## Links

| Resource | URL |
|----------|-----|
| Website | https://rootmc.net |
| Plugin catalog | https://rootmc.net/plugins/ |
| This plugin page | https://rootmc.net/plugins/rootmc-official/ |
| Suite wiki | https://rootmc.net/wiki/plugins/ |
| Player wiki | https://rootmc.net/wiki/player/ |
| Constitution | https://rootmc.net/wiki/constitution/ |
| Economy guide | https://rootmc.net/wiki/economy/ |
| Developer keys | https://rootmc.net/developer/keys/ |
| Manifest | https://rootmc.net/plugins/manifest.json |
| Play | `play.rootmc.net` |
| Live map | https://map.rootmc.net |
| API | https://api.rootmc.net |
| Discord | https://discord.gg/rFFQYrNaqS |
| GitHub (this repo) | https://github.com/RootRecord/rootmc-official |
| Releases (version notes) | https://github.com/RootRecord/rootmc-official/releases |
| BuiltByBit (paid jars) | https://builtbybit.com/ (listing coming soon) |

**Discord:** RootMC community - join for support, announcements, and governance: https://discord.gg/rFFQYrNaqS


## Documentation in this repo

- [docs/COMMANDS.md](docs/COMMANDS.md) - commands and permissions from `plugin.yml`
- [docs/LINKS.md](docs/LINKS.md) - canonical RootMC web and Discord links
- [CHANGELOG.md](CHANGELOG.md) - version history seed

## License

Copyright Root Record. All rights reserved. Public docs are for discovery; jars are distributed via BuiltByBit / licensed channels only.

