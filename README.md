# MultiCore Server

Folia-style multithreading for NeoForge 1.21.1 servers, built for the Create Economy pack (Create, Aeronautics/Sable,
Lithium, KubeJS and ~180 other mods).

**Status: early development. It has never been run on the real server. Do not install it there yet.**

## What it does

Vanilla (and NeoForge) tick every dimension one after another on a single thread. The Create Economy server runs
several dimensions (South, North, War, Shop, Creative, Nether, End), so one busy world slows every other world down.

MultiCore ticks each dimension on its own thread, like Folia's regions but with one region per dimension:

```
serial   : time sync, LevelTickEvent.Pre for every dimension      (main thread)
parallel : every dimension ticks on its own "MultiCore <dim>" thread
serial   : deferred work, serial-only dimensions, LevelTickEvent.Post (main thread)
```

Folia makes plugins opt in to being thread-safe. Mods cannot opt in, so MultiCore keeps them working on its own:

| Problem | What MultiCore does |
|---|---|
| Mods keep global state and assume one server thread | Code from mods that are not in `threadSafeMods` (their block entities, entities, block ticks, event listeners) holds one shared **mod lock** on dimension threads. Vanilla code keeps running fully in parallel. |
| Mod block entities and entities load, unload, save and get removed on the dimension thread too (Create's logistics links, Supplementaries' codec cache) | Those hooks hold the mod lock as well. |
| Mods do global work in `LevelTickEvent` | All `LevelTickEvent` handlers run on the main thread, before and after the parallel phase. |
| Portals, `changeDimension`, spawning into another dimension | Deferred to the end of the same tick, on the main thread. |
| Commands, command blocks, `/schedule`d functions | Run at the end of the tick on the main thread. |
| Reading chunks or block entities of another dimension | Allowed. Loaded chunks are read directly; unloaded ones are loaded by their owning thread without deadlocking. The first access from each mod class is logged. |
| Vanilla shared state (scoreboard, overworld saved data, loot random sequences, `LegacyRandomSource`) | Made thread-safe. |
| Sable physics (native Rapier engine with global state) | Every native call and each physics step takes the mod lock; Sable's static "currently stepping" flags become per-thread. |
| KubeJS scripts (shared Rhino context) | Every script function call takes the mod lock. |
| Create's per-dimension state (Catnip `WorldAttached`, a plain WeakHashMap) | Made thread-safe, so Create's per-world data can be used from several dimension threads. |
| Mods passing flags through static fields (Open Parties and Claims' capture fields, Steam 'n' Rails' `MixinVariables`, Create's deployer drop capture) | Those fields become per-thread, so one dimension's "in the middle of X" never leaks into another. |
| Vanilla redstone wire's `shouldSignal` switch (one field on the shared block object) | Per dimension thread, so measuring a wire in one dimension does not switch off wires in the others. |
| Create: Mobile Package Couriers' shared target map | Replaced with a concurrent map. |
| createthreadedtrains (Create's railway tick on its own worker thread) | The railway tick takes the mod lock, so trains never update at the same moment as Create code on a dimension thread. |
| dimthread (also ticks dimensions on threads) | MultiCore stands by while dimthread's `dimthread_active` gamerule is on and takes over when it is off. Never both. |
| A threading bug slips through anyway | By default MultiCore logs it, switches to vanilla single-thread ticking for the rest of the session and keeps the server running. |

## Auto-adjust under load

**One thread runs 2 to 6 dimensions.** Light dimensions (the Nether, the End, a quiet hub or shop world) share a
dimension thread and tick one after another on it, up to `maxDimensionsPerThread` (6) per thread or about
`threadTargetMs` (10 ms) of tick time; a heavy dimension (a busy overworld) gets a thread of its own. The grouping
follows the measured tick times every 5 seconds. `threadGroups` pins dimensions together (e.g.
`["minecraft:the_nether, minecraft:the_end, multiworld:hub"]`), `maxThreads` caps the threads (0 = logical cores - 2).
Dimensions on the same thread can change each other directly (no deferring). `/multicore status` shows the groups.

Each dimension is one region (`[autoAdjust]` in the config). Nothing changes while the server keeps up: auto-adjust
only acts once the server's real tick time (vanilla's 100-tick average) has stayed over `loadMs` (50 ms, i.e. TPS under
20) for `loadSeconds` (30) in a row. One good 5-second check restarts the count. Then, every 5 seconds:

- **Idle regions join the main thread.** A dimension without players whose tick costs under `idleMs` (0.5 ms) is
  ticked on the main thread after the parallel phase instead of waking its own thread. It gets its own thread back as
  soon as a player enters or it costs over `busyMs` (2 ms), at any TPS.
- **No gain, no threads.** If, over that low-TPS stretch, the parallel tick was less than `minSpeedup` (1.05x) faster
  than the same work on one thread (the sum of the dimensions' tick times minus their mod-lock waits), MultiCore ticks
  like vanilla for `retryMinutes` (10), then tries threads again (and again waits for 30 s under 20 TPS before judging).
  The wait doubles after each failed retry, up to an hour, and resets after a good stretch.

`/multicore status` shows each dimension's thread, average cost and players, and the last decision.
`/multicore auto on|off` switches it until the next restart. The GameTest server keeps it off except in its own tests.

Not done: splitting one busy dimension into several regions. On this pack nearly all of a busy dimension's work is
Create and other mods that run under the shared mod lock, so sub-regions would take turns on that lock and gain
little, while every shared per-level structure (entity sections, block updates, lighting, scheduled ticks) would need
new locking. It becomes worth it once Create is checked and added to `threadSafeMods`.

## Commands (op level 3)

- `/multicore` or `/multicore status`: mode, parallel tick time vs. single-thread time, per-dimension tick times, lock and cross-dimension counters.
- `/multicore off` / `/multicore on`: switch to vanilla ticking and back, without a restart.
- `/multicore auto off` / `/multicore auto on`: switch auto-adjust (below) until the next restart.
- `/multicore locks` (`reset`): which mods hold the shared mod lock the most. These are the ones worth checking and
  adding to `threadSafeMods` / `threadSafeListeners`.

## Config: `config/multicore-common.toml`

- `general.enabled`: master switch.
- `general.serialDimensions`: dimensions that always tick on the main thread.
- `general.fallbackToSerialOnError`: switch to vanilla ticking instead of crashing on a threading error.
- `compatibility.serializeUnsafeMods`: take the mod lock for mods not in `threadSafeMods` (keep on).
- `compatibility.threadSafeMods`: mods that were checked and may run on several dimension threads at once.
- `compatibility.threadSafeListeners`: single listeners, as `modid:EventName`, that were checked. The defaults are the
  Create, Citadel, Fluid and Blocks You Need entity-tick listeners, which only touch the entity they are given.

## Measured so far

Local test server (Ryzen 7 8845HS), the pack's 177 server-side mods loaded, 300 husks penned in each of the
overworld, nether and end, 100-tick windows:

| | Server tick |
|---|---|
| MultiCore on | 16.5 ms (17.8 ms on the second run) |
| MultiCore off | 30.4 ms |
| Speedup | about 1.8x |

With the real server's mod list (196 of its 198 jars, including dimthread switched off, servercore, createthreadedtrains
and createadvancedoptimization) the same test gives 26.5 ms on, 40.1 ms off: about **1.6x**.

Brute-force test (same mod list): every block with a block entity (3,454 blocks, 857 of the 865 block entity types)
and every entity type that can exist on its own (342) placed in the overworld, nether and end at once and ticked for
200 ticks, then again with MultiCore off. MultiCore stayed parallel the whole time, and the warnings and errors were
the same five in both runs (all the mods' own). 14 entity types were left out because they crash without the data
their item or block gives them (contraption carriers, Power Grid wires, Create's potato projectile), with or
without MultiCore.

The biggest remaining cost is mod event listeners that run on every entity tick and still take the lock (Curios,
Hypertube, Alex's Mobs, Caelus, Shield Generators, ...). Each one that is checked and allowlisted raises the speedup.

## Scheduling API (for mods and scripts)

`com.multicore.api.LevelScheduler.run(level, task)` runs a task on the thread that owns that dimension at the start of
its next tick, the equivalent of Folia's region scheduler.

## Building and testing

Needs Java 21. The build reuses the NeoForge 21.1.251 toolchain.

```bash
./gradlew build
```

The jar is written to `build/libs/`.

In-game tests (worlds tick on separate threads, cross-dimension teleports/reads/chunk loads, commands, lock exclusion,
a mob stress test) run in a throwaway GameTest server:

```bash
./gradlew runGameTestServer
```

To test with the modpack, copy its server-side mods into the local test folders first
(this only reads the modpack instance):

```powershell
./tools/sync-test-mods.ps1
```

Pack analyzer (lists every mixin by target, static non-thread-safe collections, cross-dimension calls, event listeners):

```bash
./gradlew analyzePack -Pmods="<path to a mods folder>"
```

The report goes to `build/pack-report/`.

## Installing next to the server's current mods

- **dimthread**: remove it (recommended) or run `/gamerule dimthread_active false`. While it is on, MultiCore does
  nothing and logs why. dimthread 1.2.1 for NeoForge runs every dimension's tick inside one `static synchronized`
  method, so its dimensions tick one at a time anyway; it also leaves `server.isSameThread()` false during level ticks,
  which makes mods such as Open Parties and Claims skip work there.
- **ScalableLux**: Sable 2.0.5 declares it incompatible (hard), so NeoForge refuses to start with both. Remove one.
- **Multiworld** and **create-economy-server-fixes 1.2.0** were not available locally, so they are untested
  (1.1.0 of the latter was tested). Dimensions added by Multiworld get their own thread like any other.

## Testing with the server's exact mod list

`tools/server-mods.txt` is the real server's mods folder. This copies those jars (from the CurseForge instance and
Downloads) into the local test folders and reports any it cannot find:

```powershell
./tools/sync-test-mods.ps1 -ServerList
./tools/hang-loop.ps1 -Runs 1 -Minutes 30     # GameTests, keeps the log
./tools/fuzz-report.ps1                        # brute-force results: MultiCore on vs. off
./tools/local-server.ps1                       # real dedicated server in run/server, scripted console commands
```

Small test (512 MB, only the jars in `run/gametest-light/mods`, e.g. the Create Economy Money / Ranks / Launcher mods):

```bash
./gradlew runGameTestServer -PgametestDir=run/gametest-light -PgametestRam=512M
```

`server-install/` holds the jar, default config and INSTALL.txt for the real server.

## Known limitations

- Parallelism is per dimension. A single busy dimension is not split into regions yet (that is the next stage).
- Mods outside `threadSafeMods` share one lock, so most Create content in different dimensions still takes turns.
  Checking Create and adding it to `threadSafeMods` is the biggest remaining performance win.
