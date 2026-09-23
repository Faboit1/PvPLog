# PvPLog

A lightweight combat tag plugin for Paper, Folia and Canvas (1.21 and newer, including 26.x). Hitting or getting hit by another player puts both of you in combat. Log out while in combat and you die.

## Features
- **Combat tag** on melee, projectiles, harmful splash and lingering potions, explosions (TNT, end crystals), tamed pets and, if enabled, fishing rods. The timer resets on every hit.
- **Countdown** shown in the action bar and a boss bar that drains as the timer runs out (both can be toggled).
- **Combat logging**: players who disconnect while tagged are killed, their items drop, and the last attacker gets the kill. The server broadcasts it and can run console commands you configure. Server shutdowns and (optionally) kicks are not punished.
- **Command blocking**: blacklist or whitelist mode. Namespaced commands (`/essentials:spawn`) and aliases are also caught.
- **Restrictions while in combat**: no elytra gliding, no `/fly`, blocked teleport causes, and optional ender pearl and wind charge cooldowns.
- **FriendSystem support**: if [FriendSystem](https://github.com/Faboit1/FriendSystem) is installed, friends hitting each other never get combat tagged. You can also turn on `friends.prevent-damage` to stop friendly fire completely. No extra setup needed, and PvPLog runs fine without it.
- **Folia / Canvas support**: every tagged player's timer runs on their own region thread, and nothing uses the old Bukkit scheduler.
- **Disabled worlds**, a bypass permission, and all messages in MiniMessage format.

## Commands
| Command | Permission | Description |
|---|---|---|
| `/combat` (`/ct`) | `pvplog.use` (default) | Show your remaining combat time |
| `/pvplog reload` | `pvplog.admin` | Reload config |
| `/pvplog tag <player>` | `pvplog.admin` | Force-tag a player |
| `/pvplog untag <player>` | `pvplog.admin` | Remove a player's tag |
| `/pvplog status <player>` | `pvplog.admin` | Check a player's tag |

Extra permissions: `pvplog.bypass` (never tagged) and `pvplog.bypass.commands` (can use blocked commands while tagged).

## Building
`mvn package` builds the jar at `target/PvPLog-<version>.jar`. GitHub Actions builds every push and uploads the jar as an artifact. Pushes to the default branch also publish a GitHub release `v<version>` (taken from `pom.xml`) with the jar attached. To cut a new release, bump the version in `pom.xml`.
