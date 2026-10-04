# PvPLog

A lightweight combat tag plugin for Paper, Folia and Canvas (1.21 and newer, including 26.x). Hitting or getting hit by another player puts both of you in combat. Log out while in combat and you die.

## Features
- **Combat tag** on melee, projectiles, harmful splash and lingering potions, explosions (TNT, end crystals), tamed pets and, if enabled, fishing rods. The timer resets on every hit.
- **Countdown** in the action bar (`Combat: 12s`). Each player can hide it with `/showcombatbar false`; the choice is stored on the player.
- **Combat logging**: players who disconnect while tagged are killed, their items drop, and the last attacker gets the kill. The server broadcasts it and can run console commands you configure. Kicks count too (players could get themselves kicked to escape), except staff `/kick`, restarts, whitelist and bans (`combat-log.exempt-kick-causes`); server shutdowns are never punished. Open menus are closed before the kill, and tags survive a PlugMan reload.
- **Command blocking**: blacklist or whitelist mode. Namespaced commands (`/essentials:spawn`) and aliases are also caught.
- **Restrictions while in combat**: no `/fly`, blocked teleport causes, no teleports picked from menus, dialog buttons or NPCs (homes/warps menus, RTP buttons; short anti-cheat setbacks still pass), and optional ender pearl and wind charge cooldowns. Elytras stay usable.
- **FriendSystem support**: if [FriendSystem](https://github.com/Faboit1/FriendSystem) is installed, friends hitting each other never get combat tagged. You can also turn on `friends.prevent-damage` to stop friendly fire completely. No extra setup needed, and PvPLog runs fine without it.
- **Folia / Canvas support**: every tagged player's timer runs on their own region thread, and nothing uses the old Bukkit scheduler.
- **Disabled worlds**, a bypass permission, and all messages in MiniMessage format.

## Commands
| Command | Permission | Description |
|---|---|---|
| `/combat` (`/ct`) | `pvplog.use` (default) | Show your remaining combat time |
| `/showcombatbar [true\|false]` | `pvplog.use` (default) | Show or hide your combat countdown (no argument toggles) |
| `/pvplog reload` | `pvplog.admin` | Reload config |
| `/pvplog tag <player>` | `pvplog.admin` | Force-tag a player |
| `/pvplog untag <player>` | `pvplog.admin` | Remove a player's tag |
| `/pvplog status <player>` | `pvplog.admin` | Check a player's tag |
| `/pvplog friendcheck <player> <player>` | `pvplog.admin` | Show whether FriendSystem is hooked and whether it sees the two (online) players as friends |

Extra permissions: `pvplog.bypass` (never tagged) and `pvplog.bypass.commands` (can use blocked commands while tagged).

## Building
`mvn package` builds the jar at `target/PvPLog-<version>.jar`. GitHub Actions builds every push and uploads the jar as an artifact. Pushes to the default branch also publish a GitHub release `v<version>` (taken from `pom.xml`) with the jar attached. To cut a new release, bump the version in `pom.xml`.
