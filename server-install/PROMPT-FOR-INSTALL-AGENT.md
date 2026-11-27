You are installing a server-side Minecraft mod, **MultiCore 0.4.1**, on my Create Economy server and testing it while nobody is online. I am the server owner and I am here to approve each step.

**Files:** the install kit is the folder `server-install` in my project `C:\Users\luket\mutile core server`:
- `mods/multicore-0.4.1.jar`: the mod. Its SHA-256 must be `00c3b1f3cf34441d9eac71f5bcbd6b6cd3bf0b554c751e4335bdeb6e1b54784f`.
- `config/multicore-common.toml`: its config.
- `INSTALL-FOR-AI-AGENT.md`: the full step-by-step guide with checkpoints. **Read it completely before doing anything, and follow it.** `INSTALL.txt` has the background.
- The mod list it was tested with is `tools/server-mods-2026-11-15.txt` in the project; the test pack itself is `run/gametest-next/mods`.

**The server:** `192.168.9.116` on my home network, Linux, run by Crafty 4 (server folders in `/var/opt/minecraft/crafty/crafty-4/servers/<uuid>/`; the right one has about 200 jars in `mods/` and NeoForge 21.1.251). Start and stop it through Crafty (or ask me); never kill the Java process. It is started with an explicit Java 21; don't change that.

**Rules:**
1. Ask me before every change on the server: stopping or starting it, copying or deleting a file, editing a config, any game command that changes something. Say exactly what you will do and wait for my yes. One yes covers one step.
2. Never type, store or pass a password yourself. SSH only takes a password: ask me to log in, or to run commands in a terminal you can read.
3. Back up the whole server folder (at least the worlds, `mods/`, `config/`) before installing, and check the backup's size. Never delete or overwrite world data.
4. The mod is server-side only: never put it in the modpack, client or launcher.
5. If anything looks wrong, switch it off first (`/gamerule dimthread_active true` or `/multicore off`, or stop the server and move the jar out of `mods/`), then report.
6. Report what really happened, including errors and skipped steps. Don't say a step worked unless you saw it work.

**What I want today** (nobody is online, so this is a safe test):
1. Steps 4 and 5 of the guide: compare my server's `mods/` with the tested list and report differences (new or different mods are not covered by the mod's static guard), check nothing else does the same threading, back up, then install the jar and config. **For the first start set `unlockAllMods = false` in the config.**
2. Step 6: start with MultiCore standing by (dimthread on) and check `logs/latest.log` for the lines the guide lists, plus `[MultiCore] Static guard list: 1364 fields, 124 per-world classes`. Any crash, `Mixin apply failed`, `Mod loading issue` or ERROR naming multicore: stop and report.
3. Step 7, stages A and B (dimension threads, then region threads), each for about 15 minutes. With nobody online, also ask me to join and do the usual things (ride a Create train, use a stock ticker/factory request, `/tpa`, `/home`, `/pay`, chests, portals) while you watch the log and `/multicore status`.
4. Only if A and B were clean and I say yes: stage C (stop the server, `unlockAllMods = true`, start, repeat A and B and the joining test).
5. At the end give me a short report: what you did, the `/multicore status` output for each stage, TPS/MSPT before and after (`/spark tps`), every `[MultiCore]` warning with its full stack trace, and whether you left it on or switched it off. Save any warnings with their stack traces to a file I can pass back to the developer.
