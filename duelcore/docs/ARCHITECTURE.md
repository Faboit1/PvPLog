# DuelCore – Architecture

Target: **Paper 26.2 (build 128), Minecraft 26.2, Java 25**. That is what the "Cheese PvP - Main" server (555a69cf) runs, read from its `latest.log`:
`This server is running Paper version 26.2-128 … (Implementing API version 26.2.build.128-stable)`.
Server resources: 5 GB RAM, 2 vCPU (200 %), 23 GB disk, one port (25569), standalone (no proxy forwarding).

Design rules:

* **Main thread never touches the database or disk.** Every DB call goes through one executor. A guard counts any call made from the main thread, and `/duelcore debug` shows that count.
* **Everything is config**: messages, kits, arenas, tiers, dialogs, hotbar and scoreboard, all in YAML with MiniMessage text.
* **No hard dependencies.** PlaceholderAPI is optional. JDBC drivers come from the Paper server (it bundles sqlite-jdbc and mysql-connector-j), and HikariCP is shaded and relocated.
* **Modern, quiet UI.** Menus are 1.21.6+ dialogs. Icons are atlas sprites (`minecraft:items`, `minecraft:gui`), and player faces use head objects. There's no chat prefix, no bold, and one accent colour.

## Packages

```
top.cheesesmp.duelcore
├── DuelCorePlugin            lifecycle, service wiring, reload
├── api/                      DuelCoreApi + Bukkit events (MatchStart/End, RatingChange)
├── config/                   ConfigManager, MainConfig (typed), Messages (MiniMessage templates)
├── db/                       Database (Hikari, SQLite|MySQL dialect), DbExecutor, MainThreadGuard,
│   │                         Migrations (versioned DDL), OrderedWrites
│   └── dao/                  PlayerDao, RatingDao, MatchDao, SeasonDao, LeaderboardDao
├── profile/                  PlayerProfile, KitStats, PlayerSettings, ProfileService (cache + async save)
├── kit/                      Kit, KitRules, KitLoader (YAML → Kit, vanilla item strings), KitManager
│   └── editor/               KitLayout (pure layout maths + kit fingerprint), KitLayouts (per-player cache, async
│                             save), KitEditor (the editor chest), KitPicker (dialog), KitEditorCommands, KitEditorStyle
├── arena/                    ArenaTemplate, ArenaSnapshot(+IO, .dca format), SchematicImporter (Sponge v2/v3),
│                             ArenaGenerator (built-in defaults), ArenaWorld (void world), SlotGrid,
│                             ArenaInstance, ArenaPool, BlockJobQueue (tick-budgeted), ArenaEditor, ArenaManager
├── queue/                    QueueEntry, QueueService, Matchmaker, MatchPolicy (region/ping hook),
│                             QueueMusic + MusicTracks (music disc while searching)
├── match/                    Match, Participant, MatchState, RoundResult, MatchService, MatchListener,
│                             Freeze, DuelRequestService, SpectateService, MatchResult
├── rating/                   RatingSystem, EloRating, Glicko2Rating, Tier, TierLadder, TierService, SeasonService
├── hub/                      HubService (send-to-hub, state reset), HubItems, HubListener (protection)
├── ui/                       Dialogs (Queue/Profile/Leaderboard/Settings/Spectate/Results/DuelPicker),
│                             ClickRouter (custom click keys), OpenDialogs (open dialog, live refresh),
│                             SidebarService, TagService (tab/chat/nametag), TotemPop, Sounds, Icons (sprite helpers)
├── leaderboard/              LeaderboardService (cached pages, async refresh, rank lookups)
├── command/                  Brigadier tree registered in LifecycleEvents.COMMANDS
├── hook/                     PlaceholderHook (loaded only when PlaceholderAPI exists)
├── debug/                    Diagnostics (/duelcore debug: matches, instances, chunks, entities, tasks, heap, DB),
│                             ClientInfo (client version via optional ViaVersion + brand, logged on join)
├── party/                    Party, PartyService (persistent parties, invites, chat routing, party matches),
│                             PartyDialogs, PartyCommands (/party, /pc), PartyChatListener
└── (phase 2) feed/, tournament/, web/ (REST)
```

Matches have any number of teams: a 1v1 has two, party duels two teams of several players, and a Party FFA one team
per fighter (`Match.ffa()`: one round, spawns on a ring between the two arena spawns, see `match/Teams`). Ratings
only ever apply to ranked 1v1s.

## Data model (SQLite default, MySQL optional)

Compact, integer-keyed and clustered. UUIDs are stored once as 16-byte binary. Kits and seasons map to small integer ids, so the hot tables stay narrow. SQLite runs in WAL mode (`synchronous=NORMAL`) with `WITHOUT ROWID` clustered tables.

```sql
dc_meta          (k VARCHAR(32) PK, v VARCHAR(255))                    -- schema version etc.
dc_players       (id INT PK AUTO, uuid BINARY(16) UNIQUE, name VARCHAR(16), name_lower VARCHAR(16) IDX,
                  region VARCHAR(8), country CHAR(2), settings INT, max_ping SMALLINT,
                  first_seen BIGINT, last_seen BIGINT)
dc_kits          (id SMALLINT PK, kit_key VARCHAR(32) UNIQUE)            -- stable numeric kit ids
dc_seasons       (id SMALLINT PK, name VARCHAR(32), started_at BIGINT, ended_at BIGINT NULL, legacy TINYINT)
dc_ratings       (season_id, player_id, kit_id, rating DOUBLE, rd DOUBLE, vol DOUBLE, games INT, wins INT,
                  losses INT, streak SMALLINT, best_streak SMALLINT, peak DOUBLE, tier_override TINYINT NULL,
                  updated_at BIGINT, PK(season_id, player_id, kit_id))   -- WITHOUT ROWID
                  IDX (season_id, kit_id, rating)
dc_standings     (season_id, player_id, elo SMALLINT, overall_tier TINYINT, PK(season_id, player_id))
                  IDX (season_id, elo)                                   -- overall leaderboard
dc_matches       (id BIGINT PK AUTO, season_id, kit_id, ranked TINYINT, arena VARCHAR(32), started_at BIGINT,
                  duration_ms INT, end_reason TINYINT, winner_team TINYINT, first_to TINYINT, rounds VARCHAR(64))
dc_match_players (match_id, player_id, team TINYINT, rounds_won TINYINT, hits INT, damage_dealt FLOAT,
                  damage_taken FLOAT, rating_before FLOAT, rating_after FLOAT, PK(match_id, player_id))
                  IDX (player_id, match_id)                              -- recent history
dc_parties       (id VARCHAR(36) PK, leader_id INT, open TINYINT, password VARCHAR(64) NULL, created_at BIGINT)
dc_party_members (player_id INT PK, party_id VARCHAR(36) IDX, joined_at BIGINT, chat TINYINT)  -- one party each
dc_kit_layouts   (player_id INT, kit_id SMALLINT, layout VARCHAR(255), kit_hash INT, updated_at BIGINT,
                  PK(player_id, kit_id))                                 -- kit editor, schema v7
```

A kit layout maps each of the 37 editable positions (inventory slots 0–35, offhand 36) to the position of the kit's
default loadout that goes there (or -1). `kit_hash` is a CRC-32 of the kit's item types and amounts per position;
`KitManager.apply` (every match type, every round) uses a layout only when it still matches the kit and is a complete
permutation, otherwise the default, and a layout that no longer fits the current kit is deleted with a one-time
notice. Layouts are loaded on join, cached while online and written through `OrderedWrites`.

Parties are loaded once at startup and changed in memory; their writes go through `db/OrderedWrites`, which keeps
them in order on the MySQL pool (follows, follow-graph loads and queue favourites use it too). Passwords are salted PBKDF2 hashes. A player removed from a party while offline gets
a `dc_meta` row `party-notice:<player id>` that is shown (and deleted) on their next join.

A **season reset ("beta reset")** creates a new `dc_seasons` row and marks the old one `legacy=1`. Nothing is copied. Old rows stay as the archive and can be read with `/profile <player> legacy`. Every read and write is scoped to the current season id.

## Arena instancing

* All matches run in one void world, `duelcore_arenas`. It's created by the plugin, autosave is off, and the folder is wiped on boot. The world is split into a grid of **slots** 1024 blocks apart, beyond any view distance, so matches can't see or reach each other.
* A **template** is a block snapshot: a palette plus packed indices, saved as `arenas/<name>.dca` next to `arenas/<name>.yml` (spawns, tags, type). Templates come from:
  1. the built-in generator (open `plains`, boxed `box`, `crystal` obsidian pad, `rails` for carts), so the plugin works out of the box;
  2. an admin-captured region (`/duelcore arena create/pos1/pos2/setspawn1/setspawn2/save`);
  3. a Sponge `.schem` import.
* An **instance** is a template pasted into a free slot. The paste and every reset use one routine: diff the target state against the current world. It reads `ChunkSnapshot`s on the main thread (cheap), computes the diff off-thread, and writes back in tick-budgeted batches (configurable ms/tick). Then it removes every non-player entity in the box (crystals, items, arrows, carts, TNT). The same diff runs between rounds and after the match, so resets are exact even after crystal/TNT damage.
* Instances hold plugin chunk tickets only while pasting or in use. Idle instances above `arena.keep-idle-per-template` are cleared back to air and their chunks are unloaded without saving. Per-player world borders mark the playable box.
* Pools are chosen per kit through **tags**: a kit's `arena-tags: [boxed]` matches arenas with `tags: [boxed]`. The pool grows on demand up to `arena.max-instances`.

## Queue & matchmaking

* Queue entry: kit, ranked or unranked, rating at join time, join time, region, ping, and max-ping preference.
* The **Matchmaker** runs every 20 ticks on the main thread, fully in memory. For each (kit, mode) bucket it sorts by wait time. Each player's acceptable rating window is `initial + growth × waitSeconds`, capped at `max`. Two players match when their difference fits **both** windows. Among acceptable partners it picks the lowest cost: `|Δrating| + regionPenalty + pingPenalty`, where the penalties come from the `MatchPolicy` hook and fade to zero after `relax-after` seconds. It never matches a player with themselves, and skips anyone offline, in a match or spectating.
* Leaving: the hotbar item, `/leave`, or disconnecting (the quit event removes the entry).

## Match lifecycle

```
PAIRED ── totem pop (kit item model) + title ─► ARENA_ACQUIRE (pooled or paste)
   ► ROUND_PREP: reset player state, apply kit, teleport to spawns, freeze (attribute modifiers + move guard)
   ► COUNTDOWN (5 s first round, 3 s later rounds)
   ► FIGHTING: kit rules enforced; hits and damage tracked; round timer
        ├─ lethal hit (PlayerDeathEvent cancelled) / void / timeout ─► ROUND_END
        └─ quit, kick or /leave ─► MATCH_END (forfeit)
   ROUND_END: score +1; winner reaches N? ─► MATCH_END, else arena diff-reset ─► ROUND_PREP
   MATCH_END: rating update (ranked) ─► async transactional persist ─► results dialog + summary
              ─► 3 s ─► hub (inventory/hotbar/scoreboard restored) ─► arena reset/release
```

A server shutdown during a match cancels it without any rating change. `/duelcore reload` leaves running matches alone, because each match keeps its own immutable `Kit` object.

## Ratings & tiers

* Default is **Elo**: K = 32, raised to 48 during the placement games (default 5). **Glicko-2** can be chosen in config. Each kit has its own rating, starting at 1000.
* A kit tier comes from rating thresholds on the 15-step ladder (HT1 … LT5). Thresholds live in `tiers.yml` and can be overridden per kit. Until placement is done, the tier shows `???`.
* **Overall Elo** is the average rating over the kits a player finished placement in. The overall tier uses the same Elo thresholds. Both are stored in `dc_standings` for fast leaderboards. (Tier points were dropped in schema v3.)
* `/tier set <player> <kit> <tier>` pins a tier (`tier_override`). `/tier clear` removes the pin.

## UI

* **Hub hotbar** (locked): Queue · Leaderboard · Profile · Settings · Spectate. While you're queued, the Queue item becomes "Leave queue".
* **Dialogs** use fixed custom-click keys (`duelcore:queue`, …) handled by one `PlayerCustomClickEvent` router. There are no per-click callbacks to leak, and test bots can click them with the `custom_click_action` packet.
* **Dialog flow** (`ui/dialog/OpenDialogs`, `plugin.openDialogs()`; pure rules in `DialogRefresh` and `Fingerprint`):
  every dialog uses after-action NONE, so a click leaves it on screen until the server's next `show_dialog` replaces
  it (no close-then-reopen). Every dialog is shown through `OpenDialogs.show(player, kind, …)`, which records the
  open kind and, for dialogs whose content changes, a renderer that captures what it needs (queue tab, friends
  page/filter, party page, spectate query, …). `ClickRouter` compares the player's dialog serial before and after a
  click: a click that showed no dialog closes it (`clear_dialog`), unless it called `awaitNext` because its dialog is
  loaded first (closed after 5 s, or at once through `abandon`, if that fails). `awaitNext` returns a ticket, and the
  loaded dialog (or the kit editor chest) is shown through `continueAwait(player, ticket, …)`: every show and close
  (Escape) cancels the ticket, so a load that finishes after the player closed the dialog shows nothing. Friend
  follows and reloads report failures (`FriendService.Then.failed`), which abandon the wait. Every close/exit button sends
  `duelcore:dialog/close`; the client runs a dialog's exit action on Escape (notice: its button, multi-action: the exit
  action, confirmation: the no button, with after-action CLOSE), so Escape is seen too. The open dialog is also
  forgotten on an inventory opening, quit, world change, match start, reload and disable (which closes them all), and
  on client signs that no screen is open (a movement key, an item used, a command) once the dialog had time to arrive
  and the sign to come back (ping rounded up to ticks + 3 ticks, from the last show or re-send). The spam guard never
  drops exit actions (`dialog/close`, `party/menu`), which Escape runs. A 1-tick timer re-renders refreshable dialogs
  every `dialogs.refresh.interval-ticks` and re-sends them only when their fingerprint (components, buttons, item
  parameters) changed, never while a click waits or a `DIALOG`-channel animation (the queue menu's progress fill)
  shows frames. The client builds a new screen for every `show_dialog`, which resets the scroll position and focus,
  so refreshed dialogs avoid per-second texts where they may scroll: "ago" times under a minute read "<1m" and the
  queue menu's m:ss clock is only used on tabs with at most `queue-menu.clock-max-kits` kits. Dialogs with inputs are
  never refreshed (`Rendered.inputs` drops the renderer): the live spectate list has no search box, its Search
  button opens the list with one (not refreshed).
* **Sidebar** uses a blank number format and per-line custom names. The hub shows name, tier and overall Elo, queued and live counts. In a match it shows score, round, timer and ping.
* **Queue music**: while searching, `QueueMusic` plays a random music disc to the player only (record source,
  emitted from the player, `Setting.QUEUE_MUSIC`). The next track starts from the configured track length; the music
  stops as soon as the player is matched, leaves every queue, spectates or quits (QueueService stops it on removal).
* **Hub flight**: `HubService.prepare` allows flight in the hub (`hub.allow-flight`); every way into an arena
  (spawn rise, respawn throw, kit reset) takes it away again, and plugin disable revokes it.
* **Respawn throw** (`ui/RespawnPull`, timing in `ui/ThrowMath`): arcs are raised over terrain (or become a
  teleport), high-ping players are teleported instead, the touch-down wait grows with ping, a throw the server never
  sees move is ended after `respawn-throw-stall-ticks` + ping, and the final snap sends a zero velocity one tick before
  the teleport so no velocity packet reaches the client after it.
* **Animations toolkit** (`ui/anim`): `AnimationService` (`plugin.anim()`) gives every player one animation slot per
  channel (ACTION_BAR, TITLE, DIALOG, BOSS_BAR, SOUND), driven by one shared 1-tick timer; frames run at most every
  2 ticks on visual channels, and animations end on quit, world change and disable. The hotbar hints and the queue's
  searching bar check `busy(player, ACTION_BAR)`. Pure helpers (`Ease`, `TextFx`, `BlinkFade`, `ProgressBar`, the
  `Sfx` phrase builders) are unit tested. `ProgressTracker` (`plugin.progress()`) records each rated match's
  before/after per player and kit (memory only) for the post-match `ProgressReveal` and the queue menu.
* **In-match animations** (`ui/MatchFx`, pure parts in `ui/MatchFxMath`, colours `ui/MatchFxStyle` from gui.yml
  `match-fx`): MatchService calls them for the countdown, the fight start, round over (banner instead of the action
  bar line while the match goes on), deaths (kill bar, FFA players left), the low-health heartbeat (every 5 ticks,
  spaced by `Participant.lastBeat`) and the end (victory / defeat / spectator titles, `Animations.confetti`);
  MatchListener calls the combo bar. Everything runs on the `plugin.anim()` channels, titles are sent part by part
  (`TitlePart`) so re-sent frames don't fade in again, and each method returns false when its switch is off so the
  caller keeps the plain title.
* **Match found**: a totem-pop animation shows an item that represents the kit. The client displays the held `death_protection` item, so for two ticks the offhand gets an item with `death_protection` + `item_model = <kit icon>`, then an `EntityEffect.PROTECTED_FROM_DEATH` plays.

## Testing approach

Bots can't reach the game port from the build sandbox, because the proxy only tunnels TLS. So a test-only plugin (`testkit/`) launches **mineflayer** bots inside the server container. The bots are 26.1 clients connecting through ViaBackwards to 127.0.0.1. While the server is in offline mode for testing, the testkit only lets loopback `dcbot*` names log in. The bots drive the real UI: hotbar right-click, then the dialog arrives, then they send `custom_click_action`. They also fight, disconnect on purpose, and so on. Assertions read the console, the SQLite file and `/duelcore debug` output.
