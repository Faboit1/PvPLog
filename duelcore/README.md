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
3. That's it. 15 kits and 6 terrain maps are created on the first start, and players get the hub hotbar
   (Play, Party, Leaderboard, Kit Editor, Profile, Settings, Friends, Spectate).

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

The six default maps are gently rolling natural terrain, each in its own biome (the biome is painted onto the
instance when it's pasted):

| Map | Biome | Look |
| --- | --- | --- |
| Greenfield | plains | grass, flowers, oak and birch at the edges |
| Dunes | desert | sand and sandstone, cacti, dry grass |
| Tundra | snowy_plains | snow-covered grass, spruce |
| Mesa | badlands | red sand over terracotta bands |
| Savanna | savanna | grass and coarse dirt, acacias |
| Pinewood | taiga | podzol, ferns, leaf litter, spruce |

Every map is 180×180 with about 30 blocks of ground over a bedrock floor, and 44 blocks of air above. A 3-block-thick
ring of invisible barrier runs around the edge from the bedrock up to a 3-block-thick barrier ceiling, so nobody
can dig or tower out (the top surface block of the ring's inner two columns is kept, so the edge looks natural;
the outermost column is solid barrier). The spawns are 61 blocks
apart on the middle line, the ground around them is levelled, and trees stay out of the corridor between them.
The whole map can be mined during a match and is restored between rounds. Any arena without a full bedrock bottom
layer gets one added automatically (`bedrock-floor: false` in its yml turns that off).

The default maps carry `generator-version` in their yml. When an update changes the built-in maps, every default
map written by an older version is regenerated on the next start (or `/duelcore reload`), and maps that were
dropped from the defaults are deleted. The old files are copied to `arenas/old-builtin/<name>-v<version>.yml/.dca`
first, so nothing is lost. Arenas you made yourself are never touched, and neither is a default map you saved from
the editor or marked `edited: true` in its yml.

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
the new loadout. The Cart kit's flint and steel only lights fire when `fire` is in its `allowed-blocks`: add it to an
older `kits/cart.yml` by hand.

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
| `/party` | `/p` | Party menu (see *Parties*). `create`, `invite <player>`, `accept\|deny [player]`, `join <leader>`, `leave`, `kick <player>`, `promote <player>`, `disband`, `chat`, `open`, `private`, `password`, `list`, `ffa [kit]`, `split [kit]`, `duel [leader] [kit]`, `duel accept\|deny [leader]` |
| `/pc <message>` | | Party chat. Starting a chat message with `@` does the same |
| `/duel [player] [kit]` | | Unranked challenge. Without arguments it opens a player picker. `/duel accept\|deny <player>` |
| `/settings` | | Duel requests, sidebar, sounds, chat tags, hub visibility, spectators, friend alerts, party invites, music while searching, region, country, max ping |
| `/friends [add\|remove <player>\|list]` | `/f`, `/friend` | Friends dialog: follows, followers and friends (mutual follows), online first, filter, add back, duel or spectate a friend. `add` works for offline players by exact name |
| `/follow <player>`, `/unfollow <player>` | | Follow or unfollow; when both follow each other they're friends |
| `/kit edit [kit]` | `/kiteditor [kit]` | Kit editor (see *Kit editor*): the kit picker, or the editor of one kit. `/kit` alone opens the picker too |

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
| `/animtest [on\|off\|play <preview>]` | `duelcore.animtest` | Animation test mode (see *Animations*): preview animations on yourself, simulated progress after unranked matches |
| `/duelcore debug [gc\|trace\|matches\|player <name>]` | `duelcore.admin.debug` | Health numbers (instances, chunks, entities, tasks, caches, heap, DB threads), live matches, one player's client version, brand, ping and state |

`/duelcore` has the alias `/dc`.

Every join is logged as `[join] <name> client <version> (protocol <n>), brand <brand>`: the version comes from
ViaVersion when it is installed (optional), the brand from the client (logged about two seconds later when it
arrives after the join).

## Permissions

| Permission | Default | |
| --- | --- | --- |
| `duelcore.player` | true | Parent of everything below marked "true" |
| `duelcore.party` | true | Parties: `/party`, `/pc` and the Party hotbar item (the 2v2 party queue is still to come) |
| `duelcore.queue`, `.leave`, `.profile`, `.profile.others`, `.leaderboard`, `.spectate`, `.duel`, `.settings` | true | The matching commands and hotbar items |
| `duelcore.friends` | true | `/friends`, `/follow`, `/unfollow`, the Friends hotbar item |
| `duelcore.kiteditor` | true | The kit editor: `/kit edit`, `/kiteditor` and the Kit Editor hotbar item |
| `duelcore.tournament` | true | Reserved for tournaments (not in this build yet) |
| `duelcore.spectate.bypass` | op | Spectate players who turned spectators off |
| `duelcore.tier` | op | `/tier` |
| `duelcore.animtest` | op | `/animtest` |
| `duelcore.bypass.commands` | op | Any command during a match (others are limited to `match.allowed-commands`) |
| `duelcore.hub.build` | op | Build in the hub (in creative) |
| `duelcore.chatfilter.notify` | op | See messages the chat filter blocked |
| `duelcore.chatfilter.bypass` | false | Messages skip the chat filter (not even ops have it unless given) |
| `duelcore.admin` | op | All admin permissions: `.reload`, `.sethub`, `.arena`, `.kit`, `.season`, `.rating`, `.debug`, `.match`, `.tournament` |

---

## Configuration

Every file is commented, and new keys are added to your files automatically on update. When a default changes,
`config-version` upgrades the old value once: version 2 switches `queue.allow-multiple` to true and `queue.unranked`
to false when they still have the old defaults (false / true); version 3 replaces `queue.music.tracks` with the new
11-disc list when it is still the old 21-disc default (a list you edited is kept; delete the key to get the new
default); version 4 turns `animations.countdown-pop`, `fight-sweep` and `match-point` off, so the classic start
countdown is back (they were on by default, so a server that turned them on on purpose gets them switched off too: set
them back to true after updating to keep the animated countdown). A file with a YAML error is never rewritten: the
plugin runs on the bundled defaults for it and logs the error until you fix it. Main settings:

**config.yml**

| Section | Key settings |
| --- | --- |
| `database` | `type: sqlite\|mysql`, connection and pool size |
| `hub` | world, fixed `time`, `lock-weather`, `void-y`, `show-players`, `allow-flight` (everyone flies in the hub, default on) |
| `queue` | `allow-multiple` (several kit queues at once, default on), ranked on/off, `unranked` (off: no unranked queue; `/duel` is unaffected), "searching" action bar, `music` (`enabled`, `volume`, `tracks`: `"<sound id> <seconds> [speed]"` music discs played to a player while searching; players can turn it off in their settings; `stop-client-music` stops the game's own background music every 10 s, players with Music Frequency "Constant" still hear short snippets of it) |
| `matchmaking` | `interval-ticks`, rating window (`initial`, `growth-per-second`, `max`), region and ping penalties, `max-ranked-rematches-per-day`, `log-pairings` |
| `match` | countdowns, `round-end-delay-ticks`, `return-delay-seconds`, `timeout-decision: health\|draw`, `max-rounds`, `allowed-commands`, `totem-pop`, `void-depth` |
| `animations` | see *Animations* below |
| `rating` | `system: elo\|glicko2`, `default`, `floor`, Elo K-factors (normal and provisional), Glicko-2 tau/RD/volatility |
| `season` | first season name |
| `arena` | `world`, `persistent-world`, `pregenerate-slots`, `slot-spacing`, `base-y`, `max-instances`, `keep-idle-per-template`, `prewarm`, `block-budget-ms`, `reset-between-rounds`, `view-distance` |
| `leaderboard` | `refresh-seconds`, `size`, `regions` |
| `dialogs` | `refresh.enabled`, `refresh.interval-ticks` (20): open menus whose content changes are rebuilt this often and sent again only when something visible changed (see *Menus* below) |
| `display` | tier tags in chat, tab and above heads |
| `party` | `max-size` (20), `invite-seconds` (invites and party challenges, 60), `open-by-default` |
| `debug` | `verbose` logging |

**The queue menu** (Play item, `/queue`) lists the kits by tab: Favorites (kits starred with ☆, saved per
player), Weapons, Vanilla and Skills (the kit's `category`). Clicking a kit joins or leaves its ranked queue, and a
player can search in several kits at once; the first match found takes them out of all the others. Each kit shows
how many players are searching or playing it, and the player's tier and Elo in it, or a progress bar while its
placement matches aren't played yet. *Queue All* joins (or leaves) every kit of the tab; *Keep Queuing* puts the
player back into the same queues after each match. The ✎ after a kit opens its kit editor (with `duelcore.kiteditor`), and the results
screen after a match has an *Edit kit* button for the match's kit.

**Menus** stay open while you click: a button that leads to another menu (a tab, a page, Back, a player in the
friends list, …) swaps the menu in place instead of closing and re-opening the screen, and one that does something
else (joins a match, starts spectating, sends a duel, saves settings, opens the kit editor) closes it. Close and
Escape close every menu, also while a button waits for its next menu to load (it then doesn't pop up afterwards).
While a menu is open it updates itself once a second (`dialogs.refresh`): the queue menu's search timers, player
counts and queued kits, the party menu's and the friends list's online / in-match states, a friend's or party
member's page, `/duel`'s player list, a profile's "5m ago" and the live spectate list. A menu is only sent again
when something in it changed: every update is a new screen on the client, which scrolls back to the top. So times
under a minute read "<1m" instead of counting seconds, and the queue menu's ticking search clock (0:07) is only
shown on tabs with at most `queue-menu.clock-max-kits` kits (gui.yml, 5); longer tabs show whole minutes. Menus
with a text box (Add Friends, party create / join / invite / privacy, the spectate search, settings) never update
by themselves, since that would clear what you typed (the live spectate list's *Search* opens the list with the
search box); neither does the queue menu while its progress animation plays.

**tiers.yml**

- `placement-matches`: ranked games in a kit before its tier shows; until then it's `???`.
- The rating threshold of every tier: `kit-thresholds.default`, per-kit overrides, and an optional `overall` override.
- How each tier is drawn (`format`).

Players are ranked by Elo. A tier is just the label for the Elo range a rating falls in. The overall ranking is
the overall Elo: the average rating of every kit a player has finished placement in.

**gui.yml**

- Hotbar items, each with an optional `action-bar` hint shown while it is held ("Right click to play"). Switching
  items shows the new item's hint and switching to an empty slot clears it; while queued the "searching" bar has
  priority. `enabled: false` removes an item (a deleted entry is added back from the defaults on the next load). The
  Party and Friends items are only given to players with `duelcore.party` / `duelcore.friends`.
- `queue-menu`: widths, tab icons, the placement progress bar and `clock-max-kits` (tabs with up to this many kits
  show a ticking search clock, longer ones whole minutes) of the queue menu.
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
- `party-menu`: members per page, list lengths and widths of the party menu; `sidebar.match-ffa` and
  `sidebar.spectate-ffa` are the sidebars of a Party FFA.

**chat-filter.yml**: blocks slurs and harassment, masks swearing, in chat and private messages (`/msg`, `/tell`,
`/r`, `/me`, …). Terms are written plainly and also catch leetspeak (`n1gg3r`, `f@g`), look-alike letters from
other alphabets, accents, zero-width characters, stretched letters, up to three separators between letters
(`n.i.g g-a`) and common endings. `allow` lists innocent words that contain a term (`raccoon`, `spicy`,
`Scunthorpe`). `on-block-commands` can mute or warn. Staff with `duelcore.chatfilter.notify` see blocked messages.
Note: masked words are sent as the server-side edit of a player message; clients with "Only Show Secure Chat" on
still see the original of a *masked* message (blocked messages never reach anyone).

**messages.yml**: every player-facing text. The theme tags `<accent> <text> <muted> <good> <bad>` are defined at the
top, so recolouring means editing five lines.

## Animations

Every animation has its own switch in `config.yml` `animations`; sound effects also follow each player's *Sounds*
setting. Texts are in `messages.yml` (`progress`, `animtest`), colours and the bar in `gui.yml` (`progress-reveal`).

| Key | What it does |
| --- | --- |
| `respawn-throw` (+ `-height`, `-max-ping`, `-stall-ticks`) | From round 2 on, fighters are thrown back to their spawn along an arc. Players above `-max-ping` ms (350) are teleported instead; a throw the server never sees move is ended after `-stall-ticks` + ping |
| `spawn-rise` (+ `-depth`, `-ticks`) | Round 1: each fighter rises out of a hole at their spawn |
| `death`, `round-win`, `match-win`, `fight-start` | Red burst on death, golden spiral for the round winner, fireworks for the match winner, white ring when a round starts |
| `join-title` | Title on joining the hub |
| `join-welcome` | With `join-title`: the title types out "Welcome back, name" with the best kit's tier and a soft chime ("Welcome to Cheese PvP" on a first join; `messages.yml` `hub.welcome-*`). Previews `welcome`, `welcome-first` |
| `sidebar-title` | Every few seconds a bright band sweeps over the sidebar title (`gui.yml` `sidebar.title-shimmer`). Preview `sidebar-title` |
| `tab-logo` | `<logo>` in the tab header is a colour wave that moves on with every tab refresh (`gui.yml` `tab.logo`) |
| `hub-xp-bar`, `hub-xp-fill` | The hub XP bar shows overall progress (level = overall Elo, or placement games while unranked; bar = way to the next tier). After a match it fills from the old value to the new one with XP orb sounds; matches clear it. Preview `xp-fill`; testers see the fill after every match |
| `tier-ring` | A sparkle ring in the tier's colour rises from the player's feet (only they see it) when a kit reaches a better tier. Preview `tier-ring` |
| `alert-pops` | Friend online / followed / new friend / party invite / joined alerts also pop up briefly in the action bar with a small sound, in the lobby only (`messages.yml` `hub.alerts`). Previews `alert-friend`, `alert-party` |
| `hub-sounds` | The sounds of the hub animations above |
| `match-found-sounds`, `fight-start-sounds` | Sound pools (one line picked at random) for "match found" and a round's start |
| `progress-reveal` | After a ranked match, back in the hub: the action bar counts up what the match changed (`+20% towards your tier ▰▰▰▰▱▱▱▱▱▱` during placement, `+18 Elo  1480 → 1498` after, a red `−12 Elo` counting down), then blinks and fades out. It runs under the results dialog; hotbar hints and the queue's "searching" bar wait for it |
| `progress-fade-seconds` | How long that fade takes (3) |
| `tier-up` | Title celebration for a better tier: the tier in its `tiers.yml` colour, typed in and swept by a shimmer, with a flourish |
| `placed` | The same for the first tier in a kit after the placement matches (`Placed: HT3!`) |
| `tier-down` | A quiet subtitle when a tier drops |
| `celebration-particles` | Firework bursts in front of the player (only they see them) for `tier-up` and `placed` |
| `progress-sounds` | Count ticks, flourish and demotion notes |
| `queue-progress` | Queue menu, after a match: the kit's bar fills from the old to the new value segment by segment (new segments highlighted, then settling) with "+20%", or its Elo counts up ("+18 Elo", the new tier swept by a shimmer). Plays once; any click, command, hotbar action, camera turn or step stops it |
| `searching-bar` | The "searching" action bar with a spinner, pulsing dots and slowly cycling colours (gui.yml `searching`); off = the plain bar |
| `match-found-reveal` | "MATCH FOUND" brightens with a shimmer, then the opponent and their tier are typed out (the totem pop and `match-found-sounds` stay) |
| `queue-sounds` | The queue menu's ticks and flourish, the match found whoosh and chime |
| `countdown-pop` | Off by default (the classic countdown: plain numbers, a click each second). On: the numbers pop in (white and bold, then green / yellow / red by seconds left) with a tick that rises each second |
| `fight-sweep` | Off by default (the classic `Fight` title). On: `FIGHT!` with a gradient sweep across the letters and a punch sound |
| `round-banner` | Round over, match goes on: `ROUND WON` / `ROUND LOST` types in, the score below pops (`2 — 1`); spectators see who took the round |
| `match-point` | Off by default (the subtitle always reads `Round N · first to N`). On: the countdown subtitle says `Match point` (or `Final round`) when a side is one round from winning, pulsing with `countdown-pop` |
| `combo-bar` | Attacker's action bar: the hit combo counter pops from 2 hits on; `+1 kill · 3 hit combo` when a kill doesn't end the round |
| `heartbeat` (+ `-hearts`) | At or below 3 hearts: a quiet heartbeat and a red pulse of the health in the action bar, faster when lower |
| `victory-title`, `defeat-title` | Match won: victory jingle and a shimmer over `Victory` (spectators: the winner's name). Lost: three quiet falling notes, the title greys a little |
| `victory-confetti` | Confetti raining around the match winner (colours in `gui.yml` `match-fx.confetti`) |
| `players-left` | Party FFA: `3 players left` pops when someone is eliminated |

**Animation test mode** (`/animtest on`, `duelcore.animtest`): `/animtest play <preview>` plays an animation on yourself
(`placement`, `placed`, `elo-up`, `elo-down`, `tier-up`, `tier-down`; the queue menu's `queue-placement`, `queue-placed`,
`queue-elo-up`, `queue-elo-down`, `queue-tier-up`; `searching` (ten seconds of both searching bars) and `match-found`;
the in-match ones `countdown`, `fight`, `match-point`, `round-won`, `round-lost`, `round-spectator`, `combo`, `kill`,
`heartbeat`, `victory`, `defeat`, `spectator-result`, `players-left`, and `match` for all of them in a row;
plus any a feature registers), and unranked
matches, duels and party matches end with a simulated progress reveal (ratings don't change). It lasts until
`/animtest off` or a restart.

For developers, `ui/anim` has the shared toolkit: `plugin.anim()` runs one animation per player and channel
(action bar, title, dialog, boss bar, sound; a new one replaces the old, all end on quit, world change and disable,
`busy()` tells other systems to wait), `Ease` (easings, count up/down), `TextFx` (colour lerp, typewriter, shimmer,
fade), `BlinkFade`, `ProgressBar` and `Sfx` (arpeggios, ticks, flourish). `plugin.progress()` keeps each player's
before/after per kit from their last ranked matches until the queue menu has shown it.

## Kit editor

Players arrange every kit the way they like: which item goes into which hotbar or inventory slot, and what goes into
the offhand. Every match of that kit (queue, duel, party split, party FFA, party vs party, every round) then gives
them their items that way; armour always stays in the armour slots.

- **Opening it.** The Kit Editor item (hotbar slot 4, `hotbar.kit-editor` in gui.yml) and `/kit edit` open the kit
  picker: every kit grouped like the queue menu's tabs, with its sprite, and a green ✎ on kits you saved a layout for.
  `/kit edit <kit>` (or `/kiteditor <kit>`) goes straight to one kit. The picker's kit buttons send the click
  `duelcore:kiteditor/open {kit:"<id>"}`, which any dialog can use to open a kit's editor (`duelcore:kiteditor/menu`
  opens the picker). Only in the hub, not while fighting or spectating; queued players can edit while they wait.
- **The editor** is a 6-row chest titled "Editing · <kit>" that looks like the inventory: rows 1–3 are the inventory,
  row 4 the hotbar, row 5 the armour (locked), the offhand slot (next to the "← Offhand" label) and an info item,
  row 6 the buttons *Save*, *Reset to default* (put everything back where the kit file has it), *Clear layout*
  (delete the saved layout) and *Cancel*. Click an item to pick up the whole stack, click a slot to put it down or to
  swap it with what you hold (left or right click). Shift-clicks, number keys, the offhand key, dropping, double
  clicks, dragging over several slots and clicks in your own inventory do nothing, so an item can never leave the
  editor, split or get duplicated. The Save button glints while there are unsaved changes (the title never changes
  while the editor is open: a new title re-opens the window on the client, which would swallow the next click).
- **Saving.** Save plays a chime and closes the editor. A layout is only saved when every item of the kit is placed
  exactly once (an item still on the cursor is put back into a free slot first). Closing with Escape or Cancel keeps
  the old layout. When a match is found (or a party leader starts a party match) while you're editing, the editor
  closes and the arrangement is saved, and you're told. Your own inventory (the hub hotbar) is put aside while
  editing and comes back when the editor closes (a plain hub hotbar is rebuilt so the Play / Leave queue item is
  current; anything else you carried, armour included, comes back as it was).
- **Kit changes.** A layout stores a fingerprint of the kit's items (type, amount and components such as potion
  contents and enchantments, per slot). When an admin changes a kit's items (swapping two potions of the same type
  counts too), layouts made for the old version stop being used, are deleted, and each player is told once (on
  `/duelcore reload`, on join, or at their next match of that kit). Admins' `/duelcore kit give` always gives the kit
  file's own layout.
- **Matches.** The layout is chosen at a player's first round and kept for every round of that match. A player whose
  layouts haven't loaded yet (just joined, slow database) plays that whole match with the default layout and is told.
- **Storage.** `dc_kit_layouts` (schema v7): one row per player and kit with the arrangement (for each of the 37
  slots, which slot of the kit's default loadout goes there), the kit fingerprint and the time. Layouts are loaded
  async when a player joins, kept while they're online and saved async; choosing the default deletes the row. When
  loading fails (database down) it is retried after 30 s, doubling up to 5 min, with one stack trace per outage.
- **Look.** gui.yml `kit-editor`: the items of the fixed slots, the picker's columns and width, and the sounds (open,
  pick, place, swap, deny, save, reset, clear, cancel; players' own sound setting applies). Texts are in
  messages.yml `kit-editor`. `/animtest play kit-editor-save` and `kit-editor-pick` preview the sounds.

## Parties

The Party item (second hotbar slot, `hotbar.party` in gui.yml) and `/party` open the party menu. Without a party it offers *Create Party* (with an
optional password), *Join Party* (by the leader's name, plus the password when the party has one), pending
invitations with Accept/Deny, and the open parties to join with one click.

- **Persistent.** Parties are stored in the database (`dc_parties`, `dc_party_members`) and loaded at startup.
  Members stay in their party when they log out or the server restarts; offline members are shown as offline. When
  the leader leaves, or stays offline for a minute while another member is online, the longest-standing member
  takes over (online members first). The last one to leave deletes the party. Up to `party.max-size` members (20).
- **Joining.** An invite always works (`/party invite <name>`, or the menu's list of online players who aren't in a
  party; the invite is a clickable [Accept] [Deny] chat message that expires after `invite-seconds`). *Open*
  parties are listed in everyone's menu; a password, if set, is asked from anyone who joins without an invite, also
  for private parties. Passwords are stored as salted PBKDF2 hashes and are only typed into the dialogs (the server
  logs every command line, so `/party password` and `/party join <leader>` open the dialog instead of taking one).
  Players who turned off *party invites from anyone* (setting `PARTY_INVITES`) can only be invited by their friends
  (mutual follows); friends are listed first in the menu's invite list.
- **The menu** shows the members (leader ★, head, online / offline / in match, pages of 8) and the buttons *Invite
  Player*, *Party Chat*, *Party FFA*, *Party Duel*, *Party vs Party*, *Privacy* and *Disband* / *Leave*. Buttons that
  can't be used right now are struck through and their tooltip says why. The leader clicks a member to kick them or
  make them leader.
- **Party chat.** `/pc <message>`, a chat message starting with `@`, or every message while *Party Chat* is on (saved
  per member) goes to the online party members only, with a `[Party]` prefix. It passes the chat filter like normal
  chat: blocked stays blocked, masked stays masked.
- **Party matches** (leader only, unranked, the leader picks the kit). Everyone online must be free of matches;
  queues and spectating are left automatically. *Party FFA*: everyone for themselves, one round, last one standing
  wins (spawns on a ring between the arena spawns). *Party Duel*: the online members in two random teams of equal
  size (±1). *Party vs Party*: the other leader gets a request (dialog and chat; the same leader can ask again after
  15 seconds) and the match is party against party. Team mates can't hurt each other. The party is told the result afterwards and stays together.
- **Notices.** The party hears about joins, leaves, kicks, leader changes, members coming online or going offline
  and disbanding. Someone removed or disbanded while offline is told on their next join.

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
- `/tester on|off|status|add <player>|remove <player>|list` (`duelcore.testkit.guard`, op only, alias `/testers`)
  runs that guard. `off` is open testing: anyone can join. `on` goes back to testers only. The mode is saved in
  `duelcore-test/mode.txt`. Offline mode can't verify names, so every name is locked to the IP it first joins from,
  in both modes. In open mode, new names are saved as `guest` in `allow.txt`, and those guests are refused again
  once the guard is back `on` (`/tester add` makes one a tester). Only names nobody has used can be claimed in open
  testing: operator names, whitelisted names and names that joined this server before only get in from an IP they
  are already locked to. To lock one, join once while the guard is `on` (`/tester add` also lets a name that isn't an
  operator lock on its next join). Staff whose rights come from a permission plugin and who never joined are not
  protected, so lock them before opening. When `allow.txt` can't be read, every login is refused.

Scripts:

| Script | Checks |
| --- | --- |
| `duel1.js` | Full match through the real UI (queue dialog click), respawn pull between rounds, results dialog, hub restore, profile and leaderboard dialogs |
| `forfeit.js` | Disconnect during a match gives the win to the opponent, rating saved |
| `load.js` | N bots in parallel over several kits until X matches are done (matchmaker widening, arena pooling) |
| `specsearch.js` | Spectate list sorted by Elo then name, the live list has no text box and its Search opens the one with the search box, search by name and kit, no-results text, spectating a result |
| `tagcheck.js [kit] [icon]` | Tab header/footer, slur blocked, swearing masked, clean text untouched, tab tag switches to the match kit's icon during a match and back after, kit loadout |
| `ping.js` | Server list MOTD and hover |
| `guard.js [testers\|open]` | Login guard: unknown names, bots, IP locks and (open) guests and unlocked operator names (setup in the script header) |
| `kiteditor.js [kit]` | Kit editor: `/kit edit` opens it, the hub hotbar is put aside and comes back, items are moved with window clicks, shift-click / number key / offhand key / drop / double click / clicks in the own inventory change nothing and leak nothing, save, then a `/duel` between two bots gives the items in the saved slots (and the other bot the default), Clear layout goes back to the default |
| `dialogflow.js [kit] [prefix]` | Menus stay open: a queue tab click is answered by the next menu without a `clear_dialog` in between, every kit row has a ✎ (`duelcore:kiteditor/open`), Close is a `duelcore:dialog/close` click and brings a `clear_dialog`; queued with the menu open, at least two refreshed menus with a ticking timer arrive within ~3 s and none after Close; the Friends dialog is sent again when a friend comes online |
| `party.js [kit]` | Party create/invite/accept, leader and member menus, party chat (`@`, `/pc`, toggle) only reaching members and passing the chat filter, a 3-bot Party FFA, leader succession, persistence across a rejoin, disband |

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
