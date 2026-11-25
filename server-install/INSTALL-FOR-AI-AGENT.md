# Installing MultiCore 0.4.0 on the Create Economy server: instructions for an AI agent

You are helping the server's owner install **MultiCore**, a server-side NeoForge mod that ticks the server's worlds on
several CPU threads (Folia-style). Read this whole file before doing anything. Follow it step by step and stop at
every **CHECKPOINT** to report to the owner and wait for their go-ahead.

## 1. Rules (do not break these)

1. **The owner approves every change to the live server.** Reading (listing files, reading logs and configs) is
   fine. Before you stop or start the server, copy or delete a file, edit a config, or run a command that changes
   the game, say exactly what you will do and wait for a yes. One yes covers one step, not the rest.
2. **Never authenticate with a password yourself.** SSH to the server only takes a password. Ask the owner to log in
   (or to run commands in a terminal you can read), or to set up an SSH key. Never put a password in a command, a
   file, a URL or a log.
3. **Back up before installing** (step 4). Never delete or overwrite world data.
4. **Players do not need this mod.** It is server-side only. It must not go into the modpack, the client instance
   or the launcher's mirrored mods list.
5. **If something looks wrong, turn it off first, then investigate** (section 8). The commands in section 8 work
   instantly without a restart.
6. Report what actually happened, including failures and anything you skipped. Don't claim a step worked without
   having seen it work.

## 2. What MultiCore does (so you can explain and judge it)

- **Dimension threads:** each world (the main world "South", Multiworld's shop/north/war/creative, the hub, nether,
  end) ticks on its own thread instead of one after another. Light worlds share a thread (up to 6 per thread).
- **Region threads (new in 0.3.0):** a busy world is split further. Its entities and block entities are grouped into
  regions at least 128 blocks apart, and each region ticks on its own thread. Players far apart in the same world no
  longer wait for each other.
- **Mod safety:** code from mods that aren't proven thread-safe (Create, Create Economy's mods, almost everything)
  holds one shared "mod lock", so it never runs on two threads at once. Vanilla code runs fully in parallel.
- **What stays on the main thread:** player movement and damage, commands (`/tpa`, `/home`, `/pay`...), block and
  chest interactions, moving between worlds, and mods' level-tick and server-tick events. These are things that must
  happen one at a time.
- **Auto-adjust:** worlds with no players that cost under 0.5 ms tick on the main thread. If the server stays under
  20 TPS for 30 seconds in a row and threading is not making the tick at least 1.05× faster, MultiCore ticks like
  vanilla for 10 minutes and then measures again. So it can't stay slower than vanilla.
- **Safety net:** if a threading error happens anyway, it logs it and switches itself (or just region threads) off for
  the rest of the session instead of crashing.
- **Lithium:** MultiCore turns off three Lithium options that keep one shared list per world
  (`mixin.entity.inactive_navigations`, `mixin.util.entity_movement_tracking`, `mixin.world.game_events.dispatch`).
  Lithium's log says these were overridden by `multicore`. That is expected. Lithium's hopper optimisation turns off
  with them.

## 3. The server

- Host `192.168.9.116` on the owner's home network: Lenovo ThinkCentre M75q Gen 2 Tiny, Ryzen 5 PRO 5650GE
  (6 cores / 12 threads), 16 GB RAM (about 14 GB usable), Linux.
- Runs under **Crafty 4**. Server folders are in `/var/opt/minecraft/crafty/crafty-4/servers/<uuid>/`. Find the right
  `<uuid>` by looking for the folder with the Create Economy pack (`mods/` with about 200 jars, NeoForge 21.1.251).
  Start/stop it through Crafty's web panel or ask the owner; don't kill the Java process.
- **NeoForge 21.1.251, Minecraft 1.21.1.** It's started with an explicit Java 21 (plain `java` on that machine is Java
  25). Do not change the Java the server uses.
- **dimthread** (`dimthread-NEOFORGE-mc1.21.1-v1.2.1.jar`) is installed. MultiCore steps aside while dimthread's
  gamerule `dimthread_active` is true. This is used for a safe first start (step 6).

## 4. Before installing (read-only, then CHECKPOINT)

1. **Find the files to install.** They're in this folder (`server-install/` of the MultiCore project on the owner's PC):
   - `mods/multicore-0.4.0.jar`: the mod. Its SHA-256 must be `ef0021ef7f00eec3e498dd8d6a5d38c3ec3eb81fba1bc8ba8642036af839c13f` (also in `INSTALL.txt`).
   - `config/multicore-common.toml`: the default config. Optional; the mod writes it on first start anyway.
2. **Compare the server's mods with the tested list.** List the server's `mods/` folder and compare it with
   `tools/server-mods-2026-11-15.txt` in the project (the mod list MultiCore was tested with). Report every jar that
   is on the server but not in the list, and every version difference. Mods that were tested don't need checking
   again; new or different ones are the risk. Not tested locally: `Multiworld-NeoForged-1.21.1-1.10.jar`, because it
   wasn't available.
3. **Check that nothing already provides the same threading.** MultiCore stands aside for dimthread. If another
   threading mod is present (for example "megathread" or a different "multithread" mod), report it and stop.
4. **Back up:** stop the server through Crafty (with the owner's yes), then copy the whole server folder (at least the
   world folders, `mods/`, `config/`, `server.properties`) to a dated backup folder outside the server folder. Crafty's
   own backup feature is also fine. Check the backup's size is plausible.

**CHECKPOINT 1:** report the mod differences, the backup location and size, and ask to continue.

## 5. Install (server stopped)

1. Copy `multicore-0.4.0.jar` into the server's `mods/`. If an older `multicore-*.jar` is there, move it out of
   `mods/` (keep it in the backup); never have two versions in `mods/`.
2. Copy `config/multicore-common.toml` into the server's `config/`, then in it set `unlockAllMods = false` (stages A
   and B run with mods locked, as 0.3 did; stage C unlocks them). If the server already has this file, keep a copy of
   it in the backup and tell the owner before replacing it: 0.4.0's defaults differ (unlockAllMods, lockedListeners,
   idleMs, autoScale).
3. Don't remove dimthread yet.

## 6. First start (MultiCore standing by)

1. Start the server through Crafty.
2. Watch `logs/latest.log` until the server is up ("Done (...)! For help, type "help""). Check for:
   - `[MultiCore] dimthread is installed and switched on (gamerule dimthread_active is true), so dimthread ticks the
     dimensions and MultiCore stays off.`: expected.
   - Lithium lines about options overridden by `multicore`: expected.
   - Any crash, `Mixin apply failed`, or `ERROR` mentioning `multicore`: stop and report.
3. Let it run 5 minutes with players on, as normal.

**CHECKPOINT 2:** report startup (paste the MultiCore and Lithium lines and any errors), ask whether to switch it on.

## 7. Switching it on (live, no restart; needs op level 3, from the console or an op player)

Switch it on in stages and watch each stage for at least 15–30 minutes with players online:

| Stage | Command | Expect |
|---|---|---|
| A. Dimension threads only | `/multicore regions off` then `/gamerule dimthread_active false` | `/multicore status` shows "Mode: parallel dimensions", worlds on "MultiCore ..." threads |
| B. Region threads too | `/multicore regions on` | `/multicore status` shows "Region threads: on" and, for busy worlds, lines like "entities: 812 in 3 regions on 3 threads" |
| C. Every mod unlocked | stop the server, set `unlockAllMods = true` in `config/multicore-common.toml`, start it, then stages A and B again | `/multicore status` shows "All mods unlocked", "Static guard: … methods take the lock", "Players on dimension threads: …" |

What to watch at every stage:
- `/multicore status`: mode, each world's thread and tick time, the main-thread breakdown, region splits, and
  "cross-region pauses (… timed out)". A non-zero "timed out" count should be reported.
- `/spark tps` (spark is installed): compare MSPT/TPS with what it was under dimthread.
- `logs/latest.log`: look for `[MultiCore]` warnings. "Threading error", "switching to vanilla", "Region threading is
  switched off", "could not pause", "waited 10 s for the mod lock", "waited 100 ms for the mod lock … inside a mod's own
  synchronized code", "is taking over … ms" with thread dumps, and any `ConcurrentModificationException` or "Ticking
  block entity"/"Ticking entity" crash report: report each one with its stack trace. At stage C these mean a mod that
  needs to be locked: the quick fix is adding it to `lockedContent` (`"modid:*"`) and restarting, or `unlockAllMods = false`.
- Ask the owner (or players) to try the usual things: Create trains running between stations, factory/stock ticker
  requests, `/tpa`, `/home`, `/pay`, chests, portals between worlds. These all passed automated tests, but a real client
  hasn't been tested yet.

`/multicore locks` shows which mods hold the shared lock longest. It's useful information for the owner, not something
to act on.

**CHECKPOINT 3** after each stage: report the status output, TPS before and after, and any warnings. Wait for the yes
before the next stage.

To make a stage permanent after a restart: in `config/multicore-common.toml`, `[regions] enabled = true` (or false). The
gamerule `dimthread_active false` is saved with the world.

## 8. Turning it off (instant, no restart)

| Problem | Command |
|---|---|
| Something odd and region threads are on | `/multicore regions off` |
| Still odd | `/multicore off` (plain single-thread ticking) or `/gamerule dimthread_active true` (back to dimthread) |
| Server won't start or crashes on start | Stop it, move `mods/multicore-0.4.0.jar` out of `mods/`, start again. Nothing else to undo: MultiCore stores nothing in the world (its `config/multicore-common.toml` and `multicore-map/` folder can stay or go) |

## 9. Later (only after several good days, and with the owner's yes)

- Remove dimthread from `mods/` (server stopped). Its NeoForge port gives almost no parallelism and stops some mods
  (Open Parties and Claims) from working during world ticks.
- The **thread map** web page is on port 18200 (`http://192.168.9.116:18200/`) and only answers home-network addresses.
  It shows which thread runs which world, plus chunks and players.

## 10. What has been tested (all on the owner's PC, never on the live server)

- With all 208 of the pack's mods loaded: 40 of 40 automated in-game tests pass, plus 24 unit tests. (The
  `/multicore regions` command test was added after that run; it passes in the small test pack, 41 of 41.) They
  cover:
  - every block entity and entity type of every mod, placed and ticking in every dimension, split over region threads,
    compared with MultiCore off: no errors that only happen with MultiCore
  - Create logistics: a package request sent from one region and packed by a packager on a chest in another region,
    with the exact item count
  - a real Create train, assembled at a station and driven 600 blocks to a station in another region
  - simulated players: Hub Essentials `/tpa` and `/home`, Create Economy `/pay` between regions, chests, breaking and
    placing blocks, dying in lava and respawning, riding a minecart from one region into another
  - reaching into another region (pulling from a chest there) loses and duplicates nothing
  - auto-adjust counting region threads' work correctly
- **Not tested:** a real game client connecting, and Multiworld (not available locally). Stage A/B above, with real
  players watching, is that test.
