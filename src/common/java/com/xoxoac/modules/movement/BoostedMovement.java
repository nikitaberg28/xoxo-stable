package com.xoxoac.modules.movement;

import com.xoxoac.config.ModuleTuning;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects legitimate 1.21.11 (Mounts of Mayhem) movement mechanics that produce vanilla
 * speed/verticality bursts which should NOT be flagged as flight/speed hacks:
 *
 *  - Trident Riptide (vanilla) — including throwing a Riptide trident in rain/water to launch
 *  - Spear + "Lunge" enchantment ("Рывок" in Russian) — forward dash burst on a normal jab attack
 *
 * ROOT CAUSE HISTORY:
 *  v3: armed the grace window only while player.isRiptiding() OR isHandRaised()-with-a-spear was
 *      true on the EXACT current movement tick. Too narrow — Riptide's isRiptiding() flag flips
 *      back to false a few ticks into the resulting ballistic arc, and the spear trigger wasn't
 *      even the right one to sample in the first place (see v4 below).
 *  v4: per the official Minecraft Wiki ("Lunge" enchantment page), Lunge fires on a normal JAB
 *      attack (plain left-click hit) — NOT on a held right-click/"charge" as previously assumed.
 *      There is no "charging" state to sample on a movement tick at all; the only reliable
 *      signal is the hit event itself. CombatChecks.onEntityDamage now calls markSpearAttack()
 *      on every spear hit (not just a held-right-click state), which is the sole trigger source
 *      this class needs — isHandRaised()-based sampling has been removed entirely.
 *  v5: player.isRiptiding() is a SERVER-CONFIRMED flag — the vanilla server only sets it once it
 *      agrees Riptide's activation conditions are met (in rain / in water; per the vanilla bug
 *      tracker MC-265049, this includes an actual per-block "is this position exposed to rain"
 *      check, not just "is it raining somewhere in the world"). At a spawn area built with
 *      barrier blocks overhead, the Java-side rain-exposure check correctly says "no rain here"
 *      and isRiptiding() never flips true. But GeyserMC translates Bedrock's OWN client-side
 *      weather/sky determination for barrier blocks differently — the server owner confirmed
 *      Bedrock clients visually see rain (and the client lets them use Riptide) at the exact
 *      same barrier-covered spot where Java correctly sees none. The trident's launch impulse is
 *      then applied by the Bedrock client/Geyser regardless of what the Java server's own
 *      isRiptiding() flag says, producing a real, large velocity burst with no server-side
 *      confirmation to key the exemption off — this is what caused FlyA/SpeedA to flag a
 *      completely real Riptide launch as flight/speed hacking specifically for Bedrock players
 *      at that location. The fix: for Bedrock players (BedrockPlayers.isBedrock) holding a
 *      Riptide-enchanted trident, markPossibleBedrockRiptide() is called directly from the
 *      right-click-to-throw interaction itself (see PlayerInteractEvent handling in
 *      MovementChecks), independent of isRiptiding() ever confirming anything — the same grace
 *      window is armed either way.
 */
public final class BoostedMovement {

    private final ModuleTuning tuning;
    private final Map<UUID, Integer> boostGraceTicks = new HashMap<>();
    private final Map<UUID, Integer> recentActionTicks = new HashMap<>();

    public BoostedMovement(ModuleTuning tuning) {
        this.tuning = tuning;
    }

    /**
     * Call once per movement tick. Returns true if this tick's motion should be exempted
     * from Fly/Speed/NoSlow checks because the player is (or very recently was) riptiding or
     * attacking with a spear.
     */
    public boolean isBoostedThisTick(Player player, double deltaY, double deltaXZ) {
        UUID uuid = player.getUniqueId();

        if (player.isRiptiding()) {
            recentActionTicks.put(uuid, tuning.boostRecentActionTicks());
        }

        int recentTicks = recentActionTicks.getOrDefault(uuid, 0);
        if (recentTicks > 0) {
            recentActionTicks.put(uuid, recentTicks - 1);
            boostGraceTicks.put(uuid, tuning.boostGraceTicks());
            return true;
        }

        int grace = boostGraceTicks.getOrDefault(uuid, 0);
        if (grace > 0) {
            boostGraceTicks.put(uuid, grace - 1);
            return true;
        }

        return false;
    }

    public void clear(UUID uuid) {
        boostGraceTicks.remove(uuid);
        recentActionTicks.remove(uuid);
    }

    /** Current remaining grace ticks for this player, for debug tracing only. */
    public int graceRemaining(UUID uuid) {
        return boostGraceTicks.getOrDefault(uuid, 0);
    }

    /**
     * Explicitly marks "this player just attacked with a spear" — call this from
     * CombatChecks.onEntityDamage on every spear hit (jab or charge alike), since a normal jab
     * attack is Lunge's actual trigger and there is no separate "charging" state worth sampling.
     * Lunge's own documented dash speed reaches up to 1.374 blocks/tick at level III, well above
     * SpeedA's normal walk/sprint cap — this grace window is what keeps that from being flagged.
     */
    public void markSpearAttack(Player player) {
        recentActionTicks.put(player.getUniqueId(), tuning.boostRecentActionTicks());
    }

    /**
     * Arms the same grace window as a confirmed Riptide launch, but WITHOUT requiring
     * player.isRiptiding() to ever have been true — see the v5 note in this class's Javadoc for
     * why that flag can't be trusted for Bedrock players in rain-blocked-by-barrier situations.
     * Call this from the right-click interaction itself (see MovementChecks), gated on the
     * player being a confirmed Bedrock client (BedrockPlayers.isBedrock) holding a
     * Riptide-enchanted trident, so this doesn't loosen anything for Java players (whose
     * isRiptiding() flag is trustworthy) or for Bedrock players not actually using Riptide.
     */
    public void markPossibleBedrockRiptide(Player player) {
        recentActionTicks.put(player.getUniqueId(), tuning.boostRecentActionTicks());
    }
}
