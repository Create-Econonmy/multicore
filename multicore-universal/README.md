# MultiCore Universal

Folia-style multithreading for NeoForge 1.21.1 servers that works with any mod list, without per-mod patches.
It is the general version of MultiCore Server (the parent folder), which was built and patched for one modpack.

**Status: early development. Tested locally only. Do not install it on a live server yet.**

## What it does

Vanilla ticks every dimension one after another on a single thread. MultiCore Universal ticks each dimension on its
own thread (the same engine as MultiCore Server):

```
serial   : time sync, LevelTickEvent.Pre for every dimension      (main thread)
parallel : every dimension ticks on its own "MultiCore <dim>" thread
serial   : deferred work, serial-only dimensions, LevelTickEvent.Post (main thread)
```

## How it stays safe with mods it has never seen

Mods are written for one server thread. MultiCore Server handled that with patches for the mods in one pack.
MultiCore Universal does it for every installed mod automatically:

| Layer | What it does |
|---|---|
| **Coremod** (a library jar nested in the mod jar, loaded before the game) | Indexes every installed mod and rewrites the methods of every mod class so they hold one shared **mod lock** while they run on a dimension thread. Vanilla and NeoForge code is not touched and keeps running in parallel. |
| **Mixin extension** | Other mods' mixin hooks merged into vanilla classes take the same lock when they touch shared state themselves (static fields, static collections, monitors, objects that are not their own arguments). Hooks that only read and call methods do not need it: every mod method they call locks itself. |
| **Per-thread flags** | Static fields that hooks use as flags ("this mob is griefing", "building this cache": set when a vanilla method starts, cleared when it ends) get a separate copy per dimension thread (and for the main thread while the dimension threads run), in the mixin itself or in the mod's own classes. Found automatically; e.g. 19 such fields in Open Parties and Claims and 5 in Steam 'n' Rails, which MultiCore Server had to patch by hand. |
| **Whole-method locks** | If hooks run a protocol across a vanilla method that cannot be made per thread (one hook pushes onto a static stack, another pops it), the whole vanilla method holds the lock. Each is logged at startup. |
| **Vanilla safety patches** | Shared vanilla state (scoreboard, saved data, maps, recipe cache, redstone wire, captured drops, random sources, commands, portals, cross-dimension chunk access) is made thread-safe, as in MultiCore Server. |

The lock is re-entrant through a per-thread depth counter, so nested mod calls cost a field increment. Methods that
cannot touch shared state on their own are left unwrapped: trivial ones (getters, setters, arithmetic) and read-only
ones that call no mod code. Code on other threads (world generation, networking, mods' own workers) never takes the
lock, which is exactly the vanilla behaviour those mods already handle.

### Things it guards against that broke earlier versions

- **Class-loading code**: launch plugins, transformers and mixin plugins inside mod jars (ModernFix has one) run
  while classes load; they are never locked (locking them recursed until the stack overflowed). This includes
  plugins that extend another plugin class (Sable's, Veil's) and every class a mixin config names as its plugin.
- **Static initializers** never wait for the lock (a class being initialized on one thread while the lock holder
  needs it would deadlock both).
- **Early returns that mixins add** inside an already wrapped mod method get their missing lock exit (they leaked
  the lock and froze the other dimensions), and in a synchronized method their monitor exit too. If a lock is ever
  still held when a dimension finishes its tick, it is released and the culprit logged, instead of freezing the server.
- **Hooks that mixins put at a mod method's return** (`@At("RETURN")`, `TAIL`) run inside that method's lock. They
  used to run after its exit, so a hook that threw released the lock a second time while the calling mod code still
  relied on it.
- **Enums other mods extend by rewriting their bytecode** (Steam 'n' Rails adds a roller mode to Create's this way):
  `values()`, `$values()` and the static initializer of an enum are never changed, since such rewrites find their spot
  by its exact position (they made Create's mechanical rollers fail to load).
- **Shared scratch objects**: a method that only calls vanilla or library code is normally left unwrapped, but not if
  it changes an object kept in a static field (a `static final MutableBlockPos`, a static `DecimalFormat`), and a
  tiny method that updates a field of a shared object (`INSTANCE.count++`) is not treated as trivial.
- **Mixins that match constants** (`@ModifyConstant`): the mod number is passed through `invokedynamic`, so no
  extra constant appears in mod code.
- **Huge methods** that would exceed the JVM's 64 KB limit after wrapping are left alone.
- **Explicit Java modules** among mods (rare) are left unwrapped (only their mixins are locked).

### What it deliberately leaves alone

- **Classes that reference classes not present on this side** (client-only rendering code on a server, optional
  integrations with mods that are not installed). ModLauncher recomputes stack frames for every class a coremod
  targets, and that fails, and stops the mod from loading, if it needs a missing class. MultiCore Universal runs the
  same frame computation itself at startup and leaves such classes untransformed. The log lists how many per mod.
  On a dedicated server these are almost all client code that never runs there.
- Mods listed in `transformExclusions` (escape hatch, see below).

## Cost

All mod code from every mod not listed as thread-safe runs one dimension at a time. Vanilla work (chunk ticking,
vanilla mobs, redstone, lighting, chunk generation) still runs in parallel. How much faster a server gets therefore
depends on how much of its tick is mod code. `/multicore locks` shows which mods hold the lock the most and which
methods take it most often; listing a mod in `threadSafeMods` after checking it lets its code run in parallel too
(no restart needed).

Measured with the Create Economy server's mod list (196 jars) in the mob benchmark (300 husks in each of 3
dimensions): mods hook almost every living entity's tick there (TaCZ, Curios, Sable, Lithium), so most of the work
takes turns on the lock and MultiCore Universal is **slower** than one thread for that workload. The pack-specific
MultiCore Server (which trusts mixin code it has not been told about) is the faster choice for that pack;
MultiCore Universal is the safe default for packs nobody has checked.

## Commands (op level 3)

- `/multicore` or `/multicore status`: mode, parallel vs. single-thread tick time, per-dimension times, how much code is
  protected (mods, mixin hooks, whole vanilla methods).
- `/multicore off` / `/multicore on`: switch to vanilla ticking and back, without a restart.
- `/multicore locks` (`reset`): which mods hold the shared lock the most, and (sampled) which methods take it most
  often.

## Config: `config/multicore_universal-common.toml`

- `general.enabled`, `general.serialDimensions`, `general.fallbackToSerialOnError`, `general.slowPhaseWarnMs`: as in
  MultiCore Server.
- `compatibility.serializeUnsafeMods`: take the lock for mods not in `threadSafeMods` (keep on).
- `compatibility.threadSafeMods`: mod ids whose code may run on several dimension threads at once.
- `compatibility.transformExclusions`: mod ids whose classes are not rewritten at all (restart needed). Only for a mod
  that fails to load with MultiCore Universal; its code then runs without the lock.

If the coremod is missing or fails, MultiCore Universal refuses to run dimensions in parallel and says so in the log
and in `/multicore status`.

## Building and testing

Needs Java 21.

```bash
./gradlew build
```

`build/libs/multicore_universal-<version>.jar` contains the coremod (Jar-in-Jar). Install only that jar.

Tests:

- `./gradlew test :coremod:test`: bytecode tests. Woven code is checked by the JVM verifier, a deliberately unsafe
  class is hammered from four "dimension threads" (no lost updates with the lock; a control run without it loses
  updates), plus re-entrancy, exceptions, synchronized methods, the main thread and thread-safe mods.
- `./gradlew runGameTestServer`: in-game tests. Includes `mcu_testmod`, a deliberately thread-unsafe mod (static
  HashMaps, a synchronized singleton, an event listener, global saved data, and mixins that pass the current entity
  between hooks through a static field), run in three dimensions at once; the test fails on any lost update or mixup.
  The test servers load the built jars from their `mods` folders, exactly like a real server.
- `./tools/sync-test-mods.ps1 [-ServerList | -ModsDir <folder>]`: copies a mod list into the test folders.
- `./tools/gametest.ps1`: one GameTest run with a time limit and a summary. `-Dir run\gametest-light -Ram 512M`
  runs it with only MultiCore Universal and the test mod (fits the half-gigabyte limit); the default `run\gametest`
  folder holds the full pack and needs about 6 GB.
- `./tools/local-server.ps1 -GradleArgs -PserverTestmod`: a real dedicated server in `run/server` (512 MB heap, as
  requested), driven by `tools/local-server-commands.txt`, stopped at the end.

## One thread, several dimensions

One dimension thread runs 2 to 6 dimensions: light dimensions (the Nether, the End, a quiet hub or shop world) share a
thread and tick one after another on it, up to `maxDimensionsPerThread` (6) per thread or about `threadTargetMs`
(10 ms) of tick time; a heavy dimension gets a thread of its own. The grouping follows the measured tick times every
5 seconds. `threadGroups` pins dimensions together, `maxThreads` caps the threads (0 = logical cores - 2). Dimensions on
the same thread change each other directly (no deferring). Same as MultiCore.

