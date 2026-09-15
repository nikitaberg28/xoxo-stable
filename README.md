# xoxo-AntiCheat

> **Fast, smart, and fair — keeping your server clean.**

xoxo-AntiCheat is a lightweight but powerful server-side anti-cheat plugin for Paper/Bukkit servers. Built with performance in mind, it features a novel tick-based flag queue system, instant rubberbanding, and a velocity simulation layer — so detections are accurate and false positives are kept low.

---

## 🛡️ Detection Modules

xoxo-AntiCheat employs a modular design with specialized checks across movement and combat:

### 🚶 Movement Checks (6)
- **StepA**: Detects illegitimate step heights
- **FlyA**: Detects flying without proper support or hovering
- **FlyB**: Detects the client claiming ground contact the server physics disagrees with
- **JumpA**: Detects abnormally high jumps
- **SpeedA**: Detects excessive ground speed
- **BoatFlyA**: Detects boats ascending in mid-air

### ⚔️ Combat Checks (6)
- **AutoClickerA**: Detects sustained CPS above a configurable ceiling (single-tier — see config). Requires several consecutive seconds over the limit (not just consecutive clicks), since network jitter can deliver a burst of clicks all at once without the player's actual click rate having changed.
- **ReachA**: Detects attacking entities beyond normal reach distance (applies to any target, mob or player). Reach distance is compensated by both players' ping AND their relative movement velocity — the server's snapshot of a moving target's position is stale by roughly the round-trip time, so a legitimate close-range PvP exchange routinely shows extra apparent reach for reasons that have nothing to do with cheating, and that effect scales sharply with speed (elytra-speed combat needed its own, larger allowance). No longer cancels the hit outright — flags/accumulates VL instead, with a 3-strikes-per-24h ban escalation (see Ban System below) rather than the instant ban it used to have.
- **NoWallA**: Detects attacking through a solid block the target isn't actually visible past — casts a single ray along the attacker's actual aim direction, intersects it against the target's real (padded) hitbox to find exactly where the hit would land, then checks only that specific segment for blocking terrain, with the same ping/velocity compensation as ReachA. **Players only** — mob farms pack many small-hitbox mobs into tight, repeating gap geometry (a narrow slit in a trapdoor, etc.), and that geometry reliably re-triggers this check across many targets in rapid succession in a way open PvP doesn't. No longer cancels the hit outright (flags/accumulates VL instead) — tight geometry is exactly where an occasional false reading is likeliest, while a real wall-hack still shows the pattern consistently enough to accumulate toward punishment.
- **AimAngleA**: Detects attacks when aiming significantly away from target — **players only**. Uses horizontal (yaw) angle only, not the full 3D look direction, is skipped entirely under ~1.4 blocks (point-blank range makes the angle dominated by vertical noise), and is ping-compensated the same way as ReachA/NoWallA. Also no longer cancels the hit outright, for the same reason as NoWallA.
- **MultiAuraA**: Detects hitting multiple distinct **player** targets rapidly — mobs are explicitly excluded, since hitting several mobs in a row is ordinary farm/crowd combat, not a killaura signature
- **RotationLockA**: Detects unnatural view angle locking during combat — **players only**. Compares rotation across a rolling ~500ms window of samples rather than hit-to-hit, since look and attack are separate network packets that don't update in lockstep — comparing only the immediately preceding hit produced false positives against real, actively-turning players.

**Total Checks Loaded**: 12

---

## ⚡ Key Features

### ⚡ Rubberbanding
When a movement check's buffer trips, the player is snapped back to their last valid position (`event.setTo(event.getFrom())`) on the same tick as the flag — see `tuning.movement.buffer-to-flag` in `config.yml` to adjust how forgiving that buffer is.

### 🚨 Ban System & Auto-Ban
- Punishment commands are fully configurable per module (see `config.yml` → `punishment`)
- **ReachA/FlyA/SpeedA** kick normally, but a 3rd kick across any of the three within a rolling 24-hour window escalates to an IP ban. ReachA used to skip straight to an instant ban the very first time it hit max-vl ("strongest single signal") — that turned out to be wrong in practice (see below) and was reverted to the same 3-strikes path as the other two.
- **AutoClickerA** always kicks (never bans) — click-speed alone is too easy to false-positive on

### 🎯 Combat Check Reliability (Ping/Velocity Compensation)
ReachA, NoWallA, and AimAngleA all measure distance/angle using positions captured when the SERVER processes the hit event — which is always somewhat after the attacker's actual click, by roughly their round-trip time. A completely legitimate, actively-moving PvP exchange routinely shows extra apparent reach/angle for this reason alone, and the effect scales sharply with movement speed — elytra-speed combat (10+ blocks/sec with firework boosting) can show reach readings well past 7 blocks despite a hit that genuinely landed on the attacker's screen. All three checks now add an allowance proportional to both players' ping (`Player#getPing()`) and their relative movement velocity, each independently capped so a cheater can't abuse either signal for unlimited leniency. ReachA, NoWallA, and AimAngleA no longer cancel the hit outright either — they flag and accumulate VL (which can still lead to punishment on a sustained pattern) rather than blocking a single reading that's disproportionately likely to be a false positive during fast or close combat. NoWallA is players-only (`appliesToMobs() = false`) for a related reason: mob farms pack many small-hitbox mobs into tight, repeating gap geometry, and a single geometry-edge miss re-triggers on every subsequent mob in a way open PvP doesn't.

### 📐 Velocity Context Layer
Movement checks read from a shared `MovementContext`/`BoostedMovement` layer that accounts for knockback, water exit, elytra, riptide, and spear-Lunge grace windows before flagging — reducing false positives in edge-case movement scenarios. Ground detection samples the actual collision shape of nearby blocks (not just `Material#isSolid()`), so standing on the edge of a slab, stair, or trapdoor is correctly recognised as being on the ground instead of intermittently reading as mid-air. The water-exit grace window covers a longer, more realistic swim-out (checks feet, one block up, and one block down for water — not just the feet block — over a longer grace period), since a player surfacing after swimming straight up a long water column carries real residual vertical momentum that a very short window mistook for unsupported flight. FlyA also exempts vanilla's own step-up mechanic outright (walking onto a stair/slab/carpet moves the player up to 0.6 blocks in a single resolved tick, not jump physics) regardless of recent air-tick history, and the elytra-to-chestplate swap grace window was extended to a full 2 seconds — a fast/long glide leaves real residual horizontal momentum that easily outlasted the previous, much shorter window. A dedicated Bedrock-only exemption also covers Riptide trident throws that the vanilla server doesn't itself confirm via `isRiptiding()` — a barrier-covered area can correctly read as "no rain" on the Java side while GeyserMC still lets a Bedrock client see and use rain-triggered Riptide, so the plugin now arms the same grace window directly off the right-click-with-a-Riptide-trident interaction for confirmed Bedrock clients specifically. SpeedA's base walking-speed threshold also has a small margin above the exact vanilla constant (0.435 vs. 0.42) to absorb ordinary floating-point/direction-change noise rather than flagging on a 2% deviation.

### 🔍 Suspects GUI
`/xoxo check` opens a double-chest GUI of the most-flagged players as their heads (worst offenders first, paginated). It's read-only — items can't be taken or placed. Each head's suspicion percentage is colour-coded: green near 0%, orange/yellow climbing through 30–70%, red past 80%, and dark red ("blood") past ~99% for a near-certain read. Left-clicking an online suspect's head teleports you to them, switches you to Spectator, and grants a permanent, particle-free invisibility so you can observe unnoticed. Clear it with a milk bucket or `/xoxo unvanish`.

### 📢 Live Flag Alerts
Every single failed check — not just ones that trigger a punishment — is announced individually as its own line, e.g.:
```
[LCAC] NikitaB3rg провалил проверку RotationLockA x1 Подробнее: Rotation lock across 3 hits (dyaw=0.0000 deg)
```
This is delivered to two independent destinations, controlled separately by `/xoxo alert`:
- run **as a player** with `xoxoac.helper` or op: toggles YOUR OWN chat feed for these lines
- run **from the console**: toggles whether these lines are also written to the server console log

There is no file-based log for routine flags — only actual punishments (kicks/bans) are recorded to `plugins/xoxo-AntiCheat/logs/*.json` for a durable audit trail.

### 🔧 `/xoxo` Command
A deliberately small management command.

| Subcommand      | Permission                  | Description                                                        |
|------------------|-----------------------------|----------------------------------------------------------------------|
| `/xoxo check`    | `xoxoac.helper` or op        | Opens the suspects GUI                                               |
| `/xoxo alert`    | `xoxoac.helper` or op        | Toggles your own live flag-alert chat feed                           |
| `/xoxo alert console` | op (console only)      | Toggles whether flags are also written to the server console log     |
| `/xoxo unvanish` | `xoxoac.helper` or op        | Clears the invisibility granted by the suspects GUI                  |
| `/xoxo reload`   | op                           | Reloads `config.yml`                                                 |

Permissions are managed entirely through your permissions plugin (e.g. LuckPerms):
- `xoxoac.bypass` — full anticheat bypass for the holder (skips every check, including AutoClickerA's packet-level CPS check)
- `xoxoac.helper` — access to `/xoxo check` and `/xoxo alert`

---

## 📦 Installation

1. Download the latest `.jar` from the [Releases](https://modrinth.com) page
2. Drop it into your server's `plugins/` folder
3. Restart your server
4. Grant `xoxoac.bypass` / `xoxoac.helper` to the relevant staff/trusted players through your permissions plugin (e.g. LuckPerms)

---

## 🖥️ Compatibility

| Version                              | Status             |
|--------------------------------------|--------------------|
| Paper/Bukkit/Spigot 1.20.5 (Java 21) | ✅ Supported (BETA) |
| Paper/Bukkit/Spigot 1.20.6 (Java 21) | ✅ Supported        |
| Paper/Bukkit/Spigot 1.21.1           | ✅ Supported (BETA) |
| Paper/Bukkit/Spigot 1.21.4           | ✅ Supported (BETA) |
| Paper/Bukkit/Spigot 1.21.10          | ✅ Supported        |
| Paper/Bukkit/Spigot 1.21.11          | ✅ Supported        |
| Paper/Bukkit/Spigot 26.1             | 🔜 Planned         |
| Paper/Bukkit/Spigot 26.1.2           | 🔜 Planned         |

---

## ⚠️ Known Issues

These are known bugs being actively investigated. Workarounds or fixes will be included in future updates.

- **Rubberband misfires on certain jumps** — In rare cases the rubberbanding system fires unexpectedly, causing a brief, visible stutter in player movement. This can occur when:
    - Performing large/long jumps
    - Jumping out of water
    - Jumping frame-perfectly down the edge of a block

---

## 📈 Planned Features

- [ ] Support for Version under 1.20 and 26.1, 26.1.2
- [x] Trimmed `/xoxo` command (check, alert, unvanish, reload)
- [x] Per-check enable/disable in config
- [x] Punishment escalation (repeat-offense IP bans for ReachA/FlyA/SpeedA)
- [ ] Rubberband misfire fix for edge-case jumps
- [x] Fix for leaf/plant blocks in water false positives

---

## 🤝 Contributing

Issues and pull requests are welcome! If you encounter a false positive or a missed detection, please open an issue with as much detail as possible (server version, what the player was doing, any relevant logs).

---

## 📄 License

[MIT](LICENSE) — free to use, modify, and distribute.

---

## 💙 Support

If you find xoxo-AntiCheat useful, consider starring the repository or contributing to help keep development going!

---

*xoxo-AntiCheat — because your players deserve a fair server.*