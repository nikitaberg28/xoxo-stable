package com.xoxoac.modules.combat;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Detects attacking a target through a solid block — hitting someone through a wall they aren't
 * actually visible past.
 *
 * VERSION HISTORY / WHY THIS ISN'T A FIXED-SAMPLE-POINT CHECK: an earlier version of this check
 * sampled ~7 fixed points spread across the target's hitbox (center, eye height, edges) and
 * flagged only if EVERY one of those points was blocked. That approach is wrong for exactly the
 * case it was supposed to handle well — open trapdoors, slabs, stairs. An open trapdoor's real
 * collision only occupies a small, specific sliver of its block (see Trapdoor mechanics: it
 * "flips down to the side" and only that side has collision) — a legitimate player can be aiming
 * precisely through the resulting gap (as confirmed by an actual screenshot: crosshair square on
 * the target through the gap), but if none of the 7 fixed sample points happened to land inside
 * that specific gap, every single one of them would read as blocked and the check would flag a
 * completely legitimate hit. That was the literal bug reported: "явно вижу хитбокс игрока и
 * попадаю в него... но ач отменяет удар".
 *
 * THE FIX: stop guessing sample points on the target and instead ask what vanilla itself asks —
 * does the ATTACKER'S ACTUAL AIM DIRECTION reach the target's hitbox before hitting a block?
 * This mirrors real hit detection: a single ray cast along player.getEyeLocation().getDirection()
 * (i.e. exactly where the player's crosshair is pointing) is intersected first against the
 * target's real hitbox (BoundingBox#rayTrace) to find precisely where along that ray the target
 * would be hit, and THEN a block ray-trace is run for only that shorter distance to check nothing
 * solid sits between the eye and that specific point. If the aim ray doesn't even intersect the
 * target's hitbox at all, this check has nothing meaningful to say (that's ReachA/AimAngleA's
 * job, not this one) and stays silent rather than guessing.
 */
public final class NoWallA implements CombatCheck {

    // Generous ceiling for the aim-ray/hitbox intersection search — well beyond any legitimate
    // weapon's reach (ReachA is what actually enforces reach distance; this only needs to be
    // "long enough that a legitimate hit is never missed because the ray was cut short too soon").
    private static final double MAX_AIM_RAY_DISTANCE = 8.0;

    // How much to grow the target's hitbox before intersecting the aim ray against it. Was 0.1;
    // raised after a confirmed false-positive report: attacking through a narrow gap in an open
    // trapdoor while standing on a chest, in a 3x3 trapdoor room. The gap a legitimate player can
    // actually thread a hit through is genuinely narrow, and by the time the server processes the
    // hit, both the attacker's exact aim ray (Bedrock/Geyser's own Y-axis handling differs subtly
    // from Java per the server owner's own testing) and the target's position have drifted a
    // little from what the attacker's client saw at the moment of the click — the same network
    // staleness problem as ReachA/AimAngleA (see ReachA's comment for the full explanation), just
    // compounded here because it affects BOTH the ray's origin/direction and the box it's being
    // tested against. A slightly larger padding absorbs that drift without meaningfully weakening
    // the check against an actual wall-hit, which is blocked by many blocks' worth of solid
    // geometry, not by a few centimeters.
    private static final double HITBOX_PADDING = 0.25;

    // Ping compensation on the BLOCK ray-trace distance, same root cause as ReachA/AimAngleA:
    // shortening the distance we require a clear line for gives some slack for a target whose
    // server-side position has drifted slightly stale relative to what the attacker's client saw.
    private static final double PING_SLACK_PER_100MS = 0.05;
    private static final double PING_SLACK_CAP_BLOCKS = 0.3;

    // Same idea, scaled by relative movement speed instead of ping — see ReachA's
    // velocityCompensation() for the full explanation of why elytra-speed combat needs this in
    // addition to ping alone.
    private static final double VELOCITY_SLACK_PER_BLOCK_PER_SEC = 0.03;
    private static final double VELOCITY_SLACK_CAP_BLOCKS = 0.6;

    @Override
    public String name() {
        return "NoWallA";
    }

    @Override
    public String check(Player player, LivingEntity target, CombatContext context) {
        World world = player.getWorld();
        Location eyeLoc = context.eye();
        Vector eye = eyeLoc.toVector();
        Vector aim = eyeLoc.getDirection().normalize();
        BoundingBox targetBox = target.getBoundingBox();

        // Where along the player's actual aim direction would they hit the target's real
        // hitbox? Give a little padding since a client's exact aim ray and the server's replay
        // of it can differ (Bedrock/Geyser translation, network rounding, position staleness) —
        // grow the box rather than requiring a pixel-perfect ray/hitbox intersection.
        BoundingBox paddedBox = targetBox.clone().expand(HITBOX_PADDING);
        RayTraceResult hitboxHit = paddedBox.rayTrace(eye, aim, MAX_AIM_RAY_DISTANCE);

        if (hitboxHit == null) {
            // The player's crosshair doesn't line up with the target's hitbox at all along this
            // ray. That's a targeting/reach question for AimAngleA/ReachA to judge, not a wall
            // question — nothing to flag here either way.
            return null;
        }

        double distanceToHitboxEntry = hitboxHit.getHitPosition().distance(eye);
        if (distanceToHitboxEntry < 1.0e-4) {
            return null; // attacker's eye is already inside the target's box — always legitimate
        }

        double pingSlack = pingSlack(player, target);
        double velocitySlack = velocitySlack(player, target);
        double blockCheckDistance = Math.max(0, distanceToHitboxEntry - 0.05 - pingSlack - velocitySlack);

        RayTraceResult blockHit = world.rayTraceBlocks(
                eyeLoc, aim, blockCheckDistance,
                FluidCollisionMode.NEVER, false
        );

        if (blockHit == null) {
            return null; // clear line along the actual aim direction all the way to the target
        }

        return "Aim ray blocked by a solid block before reaching target's hitbox (possible wall-hit)";
    }

    private double pingSlack(Player player, LivingEntity target) {
        int attackerPing = player.getPing();
        int targetPing = target instanceof Player targetPlayer ? targetPlayer.getPing() : 0;
        int worstPing = Math.max(attackerPing, targetPing);
        if (worstPing <= 0) return 0.0;
        double slack = (worstPing / 100.0) * PING_SLACK_PER_100MS;
        return Math.min(slack, PING_SLACK_CAP_BLOCKS);
    }

    private double velocitySlack(Player player, LivingEntity target) {
        double attackerSpeed = player.getVelocity().length();
        double targetSpeed = target.getVelocity().length();
        double relevantSpeedBlocksPerSec = Math.max(attackerSpeed, targetSpeed) * 20.0;
        double slack = relevantSpeedBlocksPerSec * VELOCITY_SLACK_PER_BLOCK_PER_SEC;
        return Math.min(slack, VELOCITY_SLACK_CAP_BLOCKS);
    }

    @Override
    public boolean cancelHit() {
        // Deliberately NOT cancelling the hit anymore, for the same reason as AimAngleA: even
        // with the padding and ping-slack fixes above, tight geometry (a 3x3 trapdoor room,
        // aiming through a narrow gap while standing on a chest) is exactly where a single
        // false reading is most likely, and this has now caused repeated legitimate-hit reports.
        // A real wall-hack shows this pattern consistently across many hits regardless of
        // geometry — that still accumulates VL and can still lead to punishment — whereas an
        // occasional reading near tight terrain should not cost the player their actual hit.
        return false;
    }

    @Override
    public boolean appliesToMobs() {
        // REVERTED from true to false (PvP-only) after a confirmed false-positive report: mob
        // farms pack many small-hitbox mobs into tight, repeating gap geometry (a narrow slit in
        // a trapdoor/fence, etc.), and every single swing that grazes the edge of that geometry
        // against ANY of those mobs re-triggers this check — unlike PvP, where a miss like this
        // is an occasional, isolated event, a farm produces the exact same geometry against many
        // targets in rapid succession, so VL raced from x1 to x10 in about one second and got the
        // player kicked purely from farming. A wall is still a wall for a mob in principle, but in
        // practice this check's aim-ray/hitbox intersection isn't reliable enough yet at the tight
        // tolerances mob-farm geometry demands, and PvP is this plugin's actual threat model for
        // wall-hacking — mobs don't fight back through walls.
        return false;
    }
}
