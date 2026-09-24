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

Every map is 64×64 with invisible barrier walls and a ceiling. Trees only grow near the edges, and the ground
around both spawns is levelled.

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
category: main                   # main | extra (extra kits sit behind "More kits")
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
  break: placed                  # none | placed (only blocks placed this round) | all
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
component string).

---

## Commands

Player commands (all players by default):

| Command | Aliases | What it does |
| --- | --- | --- |
| `/queue [kit] [ranked\|unranked]` | `/play`, `/q` | Opens the queue menu, or joins a kit's queue directly |
| `/leave` | `/forfeit` | Leaves the queue, stops spectating, or forfeits (asks to confirm within 5 s) |
| `/profile [player]` | `/stats` | Profile: overall tier and Elo, per-kit tier/rating/record, recent matches. `/profile <p> legacy` shows last season |
| `/leaderboard [kit\|overall] [region]` | `/lb`, `/top` | Leaderboards, global or per region |
| `/spectate [player]` | `/spec` | Watch a match. Without a name it opens the live list (search, sort by Elo/newest/watchers). `/spectate stop` |
| `/duel [player] [kit]` | | Unranked challenge. Without arguments it opens a player picker. `/duel accept\|deny <player>` |
| `/settings` | | Duel requests, sidebar, sounds, chat tags, hub visibility, spectators, region, country, max ping |

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
| `duelcore.party`, `duelcore.tournament` | true | Reserved for the 2v2 queue and tournaments (not in this build yet) |
| `duelcore.spectate.bypass` | op | Spectate players who turned spectators off |
| `duelcore.tier` | op | `/tier` |
| `duelcore.bypass.commands` | op | Any command during a match (others are limited to `match.allowed-commands`) |
| `duelcore.hub.build` | op | Build in the hub (in creative) |
| `duelcore.admin` | op | All admin permissions: `.reload`, `.sethub`, `.arena`, `.kit`, `.season`, `.rating`, `.debug`, `.match`, `.tournament` |

---

## Configuration

Every file is commented, and new keys are added to your files automatically on update. Main settings:

**config.yml**

| Section | Key settings |
| --- | --- |
| `database` | `type: sqlite\|mysql`, connection and pool size |
| `hub` | world, fixed `time`, `lock-weather`, `void-y`, `show-players` |
| `queue` | multiple queues at once, ranked/unranked on/off, "searching" action bar |
| `matchmaking` | `interval-ticks`, rating window (`initial`, `growth-per-second`, `max`), region and ping penalties, `max-ranked-rematches-per-day`, `log-pairings` |
| `match` | countdowns, `round-end-delay-ticks`, `return-delay-seconds`, `timeout-decision: health\|draw`, `max-rounds`, `allowed-commands`, `totem-pop`, `void-depth` |
| `animations` | `respawn-pull` (+ `-ticks`), `death`, `round-win`, `match-win`, `fight-start`, `join-title` |
| `rating` | `system: elo\|glicko2`, `default`, `floor`, Elo K-factors (normal and provisional), Glicko-2 tau/RD/volatility |
| `season` | first season name |
| `arena` | `world`, `persistent-world`, `pregenerate-slots`, `slot-spacing`, `base-y`, `max-instances`, `keep-idle-per-template`, `prewarm`, `block-budget-ms`, `reset-between-rounds`, `view-distance` |
| `leaderboard` | `refresh-seconds`, `size`, `regions` |
| `display` | tier tags in chat, tab and above heads |
| `debug` | `verbose` logging |

**tiers.yml**

- `placement-matches`: ranked games in a kit before its tier shows; until then it's `???`.
- The rating threshold of every tier: `kit-thresholds.default`, per-kit overrides, and an optional `overall` override.
- How each tier is drawn (`format`).

Players are ranked by Elo. A tier is just the label for the Elo range a rating falls in. The overall ranking is
the overall Elo: the average rating of every kit a player has finished placement in.

**gui.yml**

- Hotbar items.
- Sidebar lines for hub, queue, match and spectate.
- Chat, tab and nametag formats.
- Dialog sizes (`kit-columns`, `wide-width`, `leaderboard-lines`, `spectate-limit`).
- `motd`: two server-list lines, centered automatically, plus the hover text.

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
| `specsearch.js` | Spectate list sorted by Elo, search by name and kit, no-results text, spectating a result |
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
