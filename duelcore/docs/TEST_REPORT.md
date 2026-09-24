# DuelCore test report

All tests ran on the Cheese PvP test server (Pterodactyl, Paper 26.2 build 128, Java 25) and nowhere else. During
testing the server ran in offline mode behind the test kit's login guard, so the mineflayer bots (1.21.11 through
ViaBackwards) could join next to the human testers.

## Summary

| Area | Result |
| --- | --- |
| Gradle build | ✅ Clean, 0 compiler warnings (`-Xlint:deprecation,unchecked,removal`) |
| Unit tests | ✅ 20/20: Glicko-2 paper example, Elo, overall Elo, tier ladder, DB round trip on SQLite incl. migrations v1→v3, matchmaker windows/policies, snapshot RLE + Sponge import, bedrock floor, generated maps (fenced, spawns playable, distinct biomes), resource/message key cross-check |
| Boot | ✅ Enables in under 1.3 s. DuelCore logs no errors or warnings (other plugins' errors listed under *Environment notes*) |
| Full match through the real UI | ✅ `duel1.js`: queue dialog click → match found → countdown → first-to-3 → results dialog → hub hotbar restored → profile and leaderboard dialogs |
| Arena instances | ✅ Paste, reuse, per-round reset and post-match reset. Every reset in every run ended clean (`dirtyResets=0`) |
| Round logic | ✅ Scores like 3-0, 3-1, 3-2 and 0-3; draws on timeout decided by health |
| Ratings saved | ✅ Every finished match saved (`saved match #n as id m`); Elo ±20 at K=32, ±24 provisional |
| Tier / overall Elo recalculation | ✅ Schema v3 migration rebuilt the overall Elo of 20 players on start; the leaderboard ranks by Elo |
| Forfeit on disconnect | ✅ `forfeit.js`, plus human testers leaving mid-match (`FORFEIT_QUIT`) |
| Hub restore | ✅ Inventory, hotbar, game mode and position after every match |
| Parallel load | ✅ 12 bots, 3 kits (sword, shield, earlygame), up to 7 matches at once, TPS 20.0 |
| Matchmaker widening | ✅ Pairing logs show the window growing with wait time (e.g. 1518 vs 1458 after 23 s at ±281) |
| DB off the main thread | ✅ `db mainThread=0` in every `/duelcore debug` sample |
| Leak check (50+ matches) | ✅ 67 matches with 12 bots: instances, chunks, tickets, entities, tasks and caches back to baseline, 0 dirty resets. Heap +100 MB after GC with no DuelCore structure growing (see *Leak check*) |
| Spectate search / sort | ✅ `specsearch.js`: Elo sort, name search, kit search, no-results text, spectating a result |
| Respawn throw | ✅ Probe: 40-block throw, apex +17.4 (target 18), per-tick velocity falls by 0.08 (player gravity), lands on target. In a match: both fighters thrown each round, land within about 2 blocks, then placed exactly on spawn |
| MOTD | ✅ Server-list ping shows both centered lines and the hover text |
| Login guard / `/tester` | ✅ `guard.js`: unknown name refused, bot allowed, new tester allowed and IP-locked, wrong IP refused |
| Persistent arena world | ✅ After a restart all arenas are reused in about 3 s with 0 blocks changed; changed templates are cleared and re-pasted |

## Leak check

Run: 12 bots on sword, shield and earlygame, 19.2 minutes, **67 matches created and 67 finished** (65 decided).
38 of those ended with a stuck bot forfeiting (see *Test harness limits*), which still goes through the whole
match lifecycle. All bots played 9 to 13 matches, and up to 6 matches ran at once. Numbers from `/duelcore debug gc`:

| | Before | After 67 matches |
| --- | --- | --- |
| Live matches / queued players / duel requests | 0 / 0 / 0 | 0 / 0 / 0 |
| Arena instances | 7 (all idle) | 7 (all idle), no growth |
| Arena jobs | – | 168 done: 161 resets, **0 dirty** |
| Arena world chunks / plugin-ticket chunks | 1792 / 1008 | 1792 / 1008 |
| Entities in the arena world | 0 | 0 |
| Plugin scheduler tasks | 7 | 7 |
| DuelCore caches (profiles, sidebars, leaderboards, results, editors) | 0 | 0 |
| DB queries / on main thread / failures | 1 / 0 / 0 | 104 / **0** / 0 |
| TPS / MSPT | 20.0 | 20.0 / 0.48 (peak MSPT during the run 4.0) |
| Heap used after `System.gc()` | 527 MB | 627 MB |

Everything DuelCore owns went back to its baseline: matches, instances, chunks, tickets, entities, tasks and
caches. The heap was 100 MB higher even though none of DuelCore's structures grew. That heap also holds the other
plugins (e.g. Grim's per-player data) and JIT state, so this run alone can't separate growth from warm-up. A second
identical run is the way to confirm a flat heap. Not done yet.

## Bugs found and fixed during testing

| Found by | Problem | Fix |
| --- | --- | --- |
| Human testers | Stale results screen on rejoin | Pending results are forgotten on quit |
| Human testers | Logging in inside an old arena slot | `AsyncPlayerSpawnLocationEvent` → hub |
| Bot run | Totem pop needed a tick delay and the offhand slot directly | `setItem(40)` + per-player entity effect |
| Bot run | Respawn throw got rubber-banded by the frozen-state move check | Throws are exempt from it |
| Unit test | `TierService` crashed when `tiers.yml` had no `format` entries | Copy into an explicit `EnumMap` |
| Review | Old tier *points* still existed next to Elo | Removed; schema v3 renames the column and rebuilds standings |
| Deploy | Test-kit edit dropped the login guard (about 1 min, only Faboit joined) | Guard restored, fails closed, covered by `guard.js` |

## Environment notes (not DuelCore)

- **WorldGuardExtraFlagsPlus** throws `NoClassDefFoundError: PlaceholderExpansion` on joins because PlaceholderAPI
  is not installed. This was already there before DuelCore.
- **26.3 clients were kicked** with "invalid packet" on dialogs, later "Failed to decode packet
  'clientbound/minecraft:player_position'" together with Grim flag spam. The cause was **packetevents 2.13.x**, used
  by the standalone plugin and bundled in both Grim builds. It has no 26.3 support: it treats protocol 777 as 26.2 and
  writes 26.2 packet IDs. 26.2's `player_rotation` (0x49) is 26.3's `player_position`, and 26.3's dialog IDs don't
  exist in 26.2's table. The format of both packets is identical in 26.2 and 26.3 (Mojang classes compared) and
  ViaVersion remaps their IDs correctly, so neither DuelCore nor ViaVersion is at fault. The culprit is Grim: every
  released build bundles packetevents 2.13 and reads packets *before* ViaVersion ("pre-Via"), so for 26.3 clients it
  misreads teleports as rotations (setback loop, BadPacketsN/AimDuplicateLook spam, then the kick) and doesn't know
  the dialog IDs (GrimAnticheat/Grim#2887; the fix is the unreleased PR #2888). Applied: standalone packetevents 2.14.0
  (the first with 26.3 support; used by TotemGuard, Sentry and AntiHealthIndicator), ViaVersion 1069 and ViaBackwards
  634. Grim was briefly removed and then **kept** at the owner's request, so ViaVersion now **blocks protocol 777
  (26.3)** with the message "26.3 isn't supported yet. Please join with 26.2" until a Grim release supports 26.3. Lightning Grim 2.3.74-00dbb86 had also thrown PacketEvents
  `ArrayIndexOutOfBoundsException`s on Paper 26.2.
- **WorldGuardExtraFlagsPlus** disabled itself once its `messages-wgefp.yml` passed YAML's 3 MB limit. Its save
  doubles the apostrophes in "can't" on every write, so three messages had grown to about a million `'` each. The file
  was repaired and the three messages now say "cannot", which the bug has nothing to double. The original is kept in
  `duelcore-test/disabled/`.
- **Sentry**'s anti-bot allows about one login per IP every few seconds and has no IP exemption. It was switched off
  while the bots ran (all bots connect from 127.0.0.1) and has been switched back on.

## Test harness limits (mineflayer, not the server)

- mineflayer scales 1.21.9+ `lpVec3` velocity by 1/8000, so knockback and thrown velocity arrived about 8000× too
  weak. The harness re-applies the decoded value.
- Bots sometimes stop at 1-block terrain steps (the server corrects the move and mineflayer stops simulating). Real
  players don't see this: humans played terrain matches all afternoon. `load.js` forfeits a pair that makes no
  round progress for 60 s, so the lifecycle keeps cycling.
- Bots can't use spears (26.x jab attack), so spear matches were only played by humans.
