# DuelCore

Competitive 1v1 duels for Paper: ranked queues per kit, instanced arenas, rounds, Elo or Glicko-2 ratings, a
15-step tier ladder, seasons, leaderboards and spectating. The UI uses 1.21.6+ dialogs with atlas sprites, and
everything is in YAML with MiniMessage.

- **Server**: Paper 26.2 (built against `26.2.build.128-stable`), Java 25.
- **Optional**: PlaceholderAPI (soft dependency).
- **Storage**: SQLite by default (single file) or MySQL/MariaDB. All database work runs on its own threads, and
  `/duelcore debug` counts main-thread queries so you can prove it (it should always say `mainThread=0`).

---

## Setup

1. Build it (`./gradlew shadowJar`) or take `build/libs/DuelCore-<version>.jar`, drop it in `plugins/`, and start the
   server once. The default files are written to `plugins/DuelCore/`.
2. Stand where players should spawn and run `/duelcore sethub`. If the hub world is empty, a small platform is
   generated.
3. That's it. 15 kits and 7 terrain maps are created on the first start, and players get the hub hotbar
   (Play, Leaderboard, Profile, Settings, Spectate).

Optional:

- **MySQL**: set `database.type: mysql` and fill in `database.mysql` in `config.yml`, then restart.
- **Regions**: players pick EU/NA/… in Settings. The matchmaker prefers same-region opponents
  (`matchmaking.region`, `leaderboard.regions`).
- **PlaceholderAPI**: placeholders register automatically when PAPI is installed (see below).

### Arenas

Arenas are templates (`plugins/DuelCore/arenas/<name>.yml` + `<name>.dca` block data). Matches get their own pasted
copy (an *instance*) in the void world `duelcore:arenas`, each in its own slot 1024 blocks apart. After a round or
match only the changed blocks are reset, spread over a few ticks (`arena.block-budget-ms`).

The arena world is kept between restarts (`arena.persistent-world`). A small manifest in the world folder records
which arena sits in which slot. On start, unchanged arenas are verified and reused in a couple of seconds, and
changed or removed ones are cleared. Chunks for the first `arena.pregenerate-slots` slots are generated once in
the background, so new instances paste quickly. Delete the world folder (`world/dimensions/duelcore/arenas`) to start
over.

The seven default maps are gently rolling natural terrain, each in its own biome (the biome is painted onto the
instance when it's pasted):

| Map | Biome | Look |
| --- | --- | --- |
| Greenfield | plains | grass, flowers, oak and birch at the edges |
| Dunes | desert | sand and sandstone, cacti, dry grass |
| Tundra | snowy_plains | snow-covered grass, spruce |
| Mesa | badlands | red sand over terracotta bands |
| Blossom | cherry_grove | pink petals, cherry trees |
| Savanna | savanna | grass and coarse dirt, acacias |
| Pinewood | taiga | podzol, ferns, leaf litter, spruce |

Every map is 180×180 with a bedrock floor, invisible barrier walls and a ceiling. The spawns are 61 blocks
apart on the middle line, the ground around them is levelled, and trees stay out of the corridor between them.
The whole map can be mined during a match and is restored between rounds. Any arena without a full bedrock bottom
layer gets one added automatically (`bedrock-floor: false` in its yml turns that off).

**Building your own arena** (in the flat editor world):

```
/duelcore arena create <name>         teleports you to a fresh editor plot
/duelcore arena pos1 | pos2           corners of the region to save (look at a block or stand in it)
/duelcore arena setspawn1 | setspawn2 where the two sides spawn (your position and facing)
/duelcore arena tags terrain          which kits may use it (matched against the kit's arena-tags)
/duelcore arena buildheight 20        blocks above the lowest spawn players may build
/duelcore arena displayname <text>
/duelcore arena save                  writes arenas/<name>.yml + .dca and loads it
/duelcore arena edit <name>           paste an existing arena into the editor to change it
/duelcore arena import <name> <file>  import a Sponge schematic (v2/v3) from plugins/DuelCore/schematics
/duelcore arena world                 go to the editor world
/duelcore arena list | delete <name> | cancel
```

In `arenas/<name>.yml` you can also set `biome: minecraft:<biome>` and `enabled: false`.

### Kits

One file per kit in `plugins/DuelCore/kits/`. They reload with `/duelcore reload` without a restart. The fastest
way to make a kit is to set up your inventory in game and run `/duelcore kit save <id>`. The file format:

```yaml
display-name: "Sword"
description: "Diamond armor and a sword. Pure melee."
icon: netherite_sword            # item shown in menus; `sprite:` overrides the atlas sprite
category: weapons                # queue menu tab: weapons | vanilla | skills (old "main"/"extra" = weapons/vanilla)
order: 1
enabled: true
ranked: true
first-to: 3                      # rounds to win
round-time-limit: 180            # seconds, 0 = none (see match.timeout-decision)
arena-tags: [terrain]
rules:
  natural-regen: true
  hunger: false
  fall-damage: true
  build: false
  break: all                     # all (default: the map can be mined) | placed (only blocks placed this round) | none
  allowed-blocks: []             # when non-empty, only these can be placed
  ender-pearls: false
  pearl-cooldown: 1              # seconds
  totems: false
  crystals: false
  anchors: false
  cobwebs: false
  buckets: false
  spawn-eggs: false
  minecarts: false
  item-drops: false
  max-health: 20
loadout:
  armor:
    helmet: { item: diamond_helmet, enchants: { protection: 1 } }
  items:
    0: { item: diamond_sword, enchants: { sharpness: 1 } }
    1: "splash_potion[potion_contents={potion:'minecraft:strong_healing'}]"   # vanilla item syntax works too
  offhand: shield
```

Item fields: `item`, `amount`, `enchants`, `name`, `lore`, `potion`, `unbreakable` and `components` (vanilla
component string). Kits with `build: true` keep what they mine: the block's normal drops go straight into the
inventory.

`sprite:` takes `atlas:path`, optionally tinted with `#rrggbb` (e.g. `items:item/dragon_breath#ff6e6e` for the Pot
kit, since vanilla has no single red-potion texture). A vanilla item string can change components the YAML keys
don't cover, e.g. `"tnt_minecart[max_stack_size=64]"` to stack carts.

**Default kits.** The kit ids match MCPVP's list. Spear, Crystal, SMP, Mace, Bow and Late Game copy the MCPVP kit
previews (hotbar layout included); the others follow the widely copied MCTiers layouts, filled out with refill
stacks in the same style. All enchantments are maxed unless noted.

| Kit | Loadout |
| --- | --- |
| Sword | Diamond Prot I, diamond sword Sharpness I |
| Axe & Shield | Unenchanted diamond, shield, diamond axe and sword, bow, crossbow, 6 arrows |
| Pot | Diamond Prot IV, sword Sharp V/Sweeping III, 26 splash Healing II, 3 each Speed II, Strength II, Regeneration; 5 steak |
| Netherite Pot | Netherite Prot IV/Mending, sword Sharp V, 21 Healing II, 3 each Strength, Speed, Fire Resistance, 3 totems, 64 gapples, 128 XP |
| SMP | (MCPVP) Netherite with helmet/leggings/boots utility enchants, shield, two Sharp V/Fire Aspect II swords (one Knockback I), axe, 1 totem, 128 gapples, 32 pearls, 64 XP, the rest full of splash potions: 12 Strength II, 12 Speed II, 3 Fire Resistance |
| Diamond SMP | The SMP layout in diamond plus 128 cobwebs, 2 water and 1 lava bucket, 128 cobblestone; 1 totem, 128 gapples, 32 pearls, 64 XP, 9 Strength II, 9 Speed II, 3 Fire Resistance |
| Crystal | (MCPVP) Netherite (Blast Prot IV legs and boots), sword, Silk Touch pickaxe, 128 crystals and obsidian, 64 anchors and glowstone, 14 totems, 112 pearls, 64 gapples, 192 XP, crossbow with 64 slow-falling arrows, shield, Density mace |
| Mace | (MCPVP) Netherite, Density V + Wind Burst mace and Breach IV mace, sword, axe, shield, elytra + 3 rockets, 2 totems, 128 wind charges, 64 pearls, 128 gapples; no potions |
| Cart | Netherite (Blast Prot legs and boots), Power V/Flame/Punch/Infinity bow, Piercing IV crossbow, 128 stacked TNT carts, 192 rails, planks, cobwebs, flint and steel, 2 totems, 128 gapples, 48 pearls, 128 XP, 4 Strength II, 4 Speed II, 3 Fire Resistance |
| Creeper | Netherite (Blast Prot legs and boots), 128 creeper eggs, 2 flint and steel, sword, axe, shield, 2 totems, 128 gapples, 48 pearls, 128 cobwebs, planks, 128 XP, 4 Strength II, 4 Speed II, 2 Fire Resistance |
| Spear | (MCPVP) Netherite, spear Lunge III/Sharp V/Unbreaking III, Density + Wind Burst mace, sword, axe, shield, 2 totems, 64 pearls, 256 wind charges, 64 gapples, 64 steak |
| Early Game | The Late Game layout in iron: Iron Prot II, shield, iron sword (Sharp I) and axe, bow, crossbow, 16 arrows, 4 gapples, steak, planks, cobblestone, 2 water and 1 lava bucket, iron pickaxe and shovel |
| Late Game | (MCPVP UHC-style diamond) Diamond Prot II, shield, Sharp III sword, axe, Power III bow, Piercing crossbow, 16 arrows, 8 gapples, 16 steak, planks, cobblestone, 8 cobwebs, 4 water and 2 lava buckets, pickaxe, Efficiency shovel; no pearls or totems |
| End Game | Netherite, crystal hotbar (128 crystals and obsidian, 64 anchors and glowstone), sword, axe, pickaxe, Density mace + 64 wind charges, elytra + 64 rockets, shield, 8 totems, 64 pearls, 128 gapples, 128 XP, 32 cobwebs, 2 each Strength II, Speed II, Fire Resistance |
| Bow | (MCPVP) Iron Projectile Prot II, Power V/Punch bow, 64 arrows; nothing else (no food, no regeneration) |

Kit files already on a server are not overwritten on update; delete a file (or copy the new default over it) to get
the new loadout.

---

## Commands

Player commands (all players by default):

| Command | Aliases | What it does |
| --- | --- | --- |
| `/queue [kit]` | `/play`, `/q` | Opens the queue menu, or joins a kit's (ranked) queue directly |
| `/leave` | `/forfeit` | Leaves the queue, stops spectating, or forfeits (asks to confirm within 5 s) |
| `/profile [player]` | `/stats` | Profile: overall tier and Elo, per-kit tier/rating/record, recent matches. `/profile <p> legacy` shows last season |
| `/leaderboard [kit\|overall] [region]` | `/lb`, `/top` | Leaderboards, global or per region |
| `/spectate [player]` | `/spec` | Watch a match. Without a name it opens the live list (search; highest Elo first, then by name). `/spectate stop` |
| `/duel [player] [kit]` | | Unranked challenge. Without arguments it opens a player picker. `/duel accept\|deny <player>` |
| `/settings` | | Duel requests, sidebar, sounds, chat tags, hub visibility, spectators, friend alerts, party invites, region, country, max ping |
| `/friends [add\|remove <player>\|list]` | `/f`, `/friend` | Friends dialog: follows, followers and friends (mutual follows), online first, filter, add back, duel or spectate a friend. `add` works for offline players by exact name |
| `/follow <player>`, `/unfollow <player>` | | Follow or unfollow; when both follow each other they're friends |

Staff:

| Command | Permission | |
| --- | --- | --- |
| `/tier set <player> <kit> <tier>` / `clear` / `info <player>` | `duelcore.tier` | Pin a kit tier (e.g. after a tier test) |
| `/duelcore reload` | `duelcore.admin.reload` | Reload config, messages, gui, tiers, kits and arenas |
| `/duelcore sethub` | `duelcore.admin.sethub` | Set the hub spawn |
| `/duelcore arena …` | `duelcore.admin.arena` | See *Arenas* |
| `/duelcore kit list\|give <id>\|save <id>` | `duelcore.admin.kit` | Kits |
| `/duelcore season info\|reset <name> confirm\|recalc` | `duelcore.admin.season` | New season: ratings reset, the old season stays viewable as "legacy". `recalc` rebuilds the overall tiers after changing tiers.yml |
| `/duelcore player <name> setrating <kit> <r>\|setgames <kit> <n>\|setregion <r>\|setcountry <cc>` | `duelcore.admin.rating` | Edit a player |
| `/duelcore forceend <player>` | `duelcore.admin.match` | End a match without rating changes |
| `/duelcore debug [gc\|trace\|matches]` | `duelcore.admin.debug` | Health numbers (instances, chunks, entities, tasks, caches, heap, DB threads), live matches |

`/duelcore` has the alias `/dc`.

## Permissions

| Permission | Default | |
| --- | --- | --- |
| `duelcore.player` | true | Parent of everything below marked "true" |
| `duelcore.queue`, `.leave`, `.profile`, `.profile.others`, `.leaderboard`, `.spectate`, `.duel`, `.settings` | true | The matching commands and hotbar items |
| `duelcore.friends` | true | `/friends`, `/follow`, `/unfollow`, the Friends hotbar item |
| `duelcore.party`, `duelcore.tournament` | true | Reserved for the 2v2 queue and tournaments (not in this build yet) |
| `duelcore.spectate.bypass` | op | Spectate players who turned spectators off |
| `duelcore.tier` | op | `/tier` |
| `duelcore.bypass.commands` | op | Any command during a match (others are limited to `match.allowed-commands`) |
| `duelcore.hub.build` | op | Build in the hub (in creative) |
| `duelcore.chatfilter.notify` | op | See messages the chat filter blocked |
| `duelcore.chatfilter.bypass` | false | Messages skip the chat filter (not even ops have it unless given) |
| `duelcore.admin` | op | All admin permissions: `.reload`, `.sethub`, `.arena`, `.kit`, `.season`, `.rating`, `.debug`, `.match`, `.tournament` |

---

## Configuration

Every file is commented, and new keys are added to your files automatically on update. Main settings:

**config.yml**

| Section | Key settings |
| --- | --- |
| `database` | `type: sqlite\|mysql`, connection and pool size |
| `hub` | world, fixed `time`, `lock-weather`, `void-y`, `show-players` |
| `queue` | `allow-multiple` (several kit queues at once, default on), ranked on/off, `unranked` (off: no unranked queue; `/duel` is unaffected), "searching" action bar |
| `matchmaking` | `interval-ticks`, rating window (`initial`, `growth-per-second`, `max`), region and ping penalties, `max-ranked-rematches-per-day`, `log-pairings` |
| `match` | countdowns, `round-end-delay-ticks`, `return-delay-seconds`, `timeout-decision: health\|draw`, `max-rounds`, `allowed-commands`, `totem-pop`, `void-depth` |
| `animations` | `respawn-throw` (+ `-height`), `spawn-rise` (+ `-depth`, `-ticks`), `death`, `round-win`, `match-win`, `fight-start`, `join-title`, `match-found-sounds`, `fight-start-sounds` |
| `rating` | `system: elo\|glicko2`, `default`, `floor`, Elo K-factors (normal and provisional), Glicko-2 tau/RD/volatility |
| `season` | first season name |
| `arena` | `world`, `persistent-world`, `pregenerate-slots`, `slot-spacing`, `base-y`, `max-instances`, `keep-idle-per-template`, `prewarm`, `block-budget-ms`, `reset-between-rounds`, `view-distance` |
| `leaderboard` | `refresh-seconds`, `size`, `regions` |
| `display` | tier tags in chat, tab and above heads |
| `debug` | `verbose` logging |

**The queue menu** (Play item, `/queue`) lists the kits by tab: Favorites (kits starred with ☆, saved per
player), Weapons, Vanilla and Skills (the kit's `category`). Clicking a kit joins or leaves its ranked queue, and a
player can search in several kits at once; the first match found takes them out of all the others. Each kit shows
how many players are searching or playing it, and the player's tier and Elo in it, or a progress bar while its
placement matches aren't played yet. *Queue All* joins (or leaves) every kit of the tab; *Keep Queuing* puts the
player back into the same queues after each match.

**tiers.yml**

- `placement-matches`: ranked games in a kit before its tier shows; until then it's `???`.
- The rating threshold of every tier: `kit-thresholds.default`, per-kit overrides, and an optional `overall` override.
- How each tier is drawn (`format`).

Players are ranked by Elo. A tier is just the label for the Elo range a rating falls in. The overall ranking is
the overall Elo: the average rating of every kit a player has finished placement in.

**gui.yml**

- Hotbar items, each with an optional `action-bar` hint shown while it is held ("Right click to play"). Switching
  items shows the new item's hint and switching to an empty slot clears it; while queued the "searching" bar has
  priority.
- `queue-menu`: widths, tab icons and the placement progress bar of the queue menu.
- Sidebar lines for hub, queue, match and spectate.
- Tier tags (`tags`): the icon of a kit followed by the tier in it (`icon-tier: "<icon><tier>"`). In the hub a
  player shows their best kit (best tier, then highest rating), during a match the match's kit with the tier they had
  when it started. Chat, tab and nametag formats. The tab list is sorted by tier, best first. Players spectating a
  match show like vanilla spectators (`tab-spectator`: grey, italic, listed last).
- Tab header and footer (`tab`): online/live/queued counts, ping, TPS and spectators (`<spectators>` in total,
  `<watching>` for the player's own match in `footer-match`, the footer used during a match or while spectating).
  Existing servers keep their old `footer`; add the `<spectators>` line from the bundled gui.yml to show it in the hub.
- Dialog sizes (`kit-columns`, `wide-width`, `leaderboard-lines`, `spectate-limit`).
- `motd`: two server-list lines, centered automatically, plus the hover text.

**chat-filter.yml**: blocks slurs and harassment, masks swearing, in chat and private messages (`/msg`, `/tell`,
`/r`, `/me`, …). Terms are written plainly and also catch leetspeak (`n1gg3r`, `f@g`), look-alike letters from
other alphabets, accents, zero-width characters, stretched letters, up to three separators between letters
(`n.i.g g-a`) and common endings. `allow` lists innocent words that contain a term (`raccoon`, `spicy`,
`Scunthorpe`). `on-block-commands` can mute or warn. Staff with `duelcore.chatfilter.notify` see blocked messages.
Note: masked words are sent as the server-side edit of a player message; clients with "Only Show Secure Chat" on
still see the original of a *masked* message (blocked messages never reach anyone).

**messages.yml**: every player-facing text. The theme tags `<accent> <text> <muted> <good> <bad>` are defined at the
top, so recolouring means editing five lines.

## PlaceholderAPI

`%duelcore_elo%` (overall Elo), `%duelcore_tier%`, `%duelcore_tier_formatted%`,
`%duelcore_wins%`, `%duelcore_losses%`, `%duelcore_region%`, `%duelcore_in_match%`, `%duelcore_queued%`,
`%duelcore_live%`, and per kit `%duelcore_tier_<kit>%`, `%duelcore_rating_<kit>%`, `%duelcore_wins_<kit>%`,
`%duelcore_losses_<kit>%`, `%duelcore_games_<kit>%`. Only cached data of online players is used, so placeholders
never wait on the database.

## Developer API

`DuelCoreApi` is registered as a Bukkit service (`getServicesManager().load(DuelCoreApi.class)`) and has `profile`,
`overallTier`, `kitTier`, `elo` (the overall Elo), `match` and `liveMatches`. `setMatchPolicy` plugs in
your own pairing rules. `MatchStartEvent` and `MatchEndEvent` are fired for every match.

---

## Testing

`testkit/` is a separate helper plugin (`DuelCoreTestKit`) plus mineflayer scripts for testing on a real server:

- `/dctest run <id> <script> [args]`, `/dctest stop <id>`, `/dctest ps`. These are console only.
- Bots log in through an offline-mode guard that only admits loopback `dcbot*` names and the names listed in
  `duelcore-test/allow.txt`.

Scripts:

| Script | Checks |
| --- | --- |
| `duel1.js` | Full match through the real UI (queue dialog click), respawn pull between rounds, results dialog, hub restore, profile and leaderboard dialogs |
| `forfeit.js` | Disconnect during a match gives the win to the opponent, rating saved |
| `load.js` | N bots in parallel over several kits until X matches are done (matchmaker widening, arena pooling) |
| `specsearch.js` | Spectate list sorted by Elo then name, search by name and kit, no-results text, spectating a result |
| `tagcheck.js [kit] [icon]` | Tab header/footer, slur blocked, swearing masked, clean text untouched, tab tag switches to the match kit's icon during a match and back after, kit loadout |
| `ping.js` | Server list MOTD and hover |

Never install the test kit on a production server.

## Known limitations

- **Bots and spears**: bots can't use spears (the 26.x spear jab isn't a normal attack packet), so spear matches were
  only played by humans.
- **First paste after a fresh install**: this waits for world generation, which can take 20+ seconds on a slow host
  while pregeneration runs. After that, arenas are reused from disk.
- **Placement**: blocks can only be placed inside the arena box up to `build-height`. Arenas taller than the box
  need a larger template.
- **One server**: this is a single-server plugin. Queues don't span servers yet (see `docs/ARCHITECTURE.md` for the
  Velocity plan).
- **Not built yet**: party (2v2) queue, tournaments and the read-only web API. Their permissions and config
  sections exist but do nothing in this build.

## What to build next

1. 2v2 party queue on top of the existing team support in matches.
2. Bracket tournaments with spectator broadcast.
3. Read-only REST/JSON API (leaderboards, profiles, live matches) with a token.
4. Velocity network: a central queue service and region servers (design notes in `docs/ARCHITECTURE.md`).
5. Replays or a kill-cam from recorded positions.
