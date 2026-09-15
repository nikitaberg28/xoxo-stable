package com.xoxoac.modules.combat;

import com.xoxoac.config.ModuleTuning;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public final class AimAngleA implements CombatCheck {

    // Deliberately PvP-only (see CombatCheck#appliesToMobs default) — hitting a mob without
    // looking straight at it (peripheral-vision hits while farming, mobs stacked at your feet)
    // is normal; precise aim only really matters as a cheat signal against a player target.
    private final ModuleTuning tuning;

    // Below this distance (blocks, eye-to-target-center), the 3D look-dot angle becomes
    // geometrically unreliable rather than a cheat signal: when two players are pressed against
    // each other, ANY small vertical difference between the attacker's eye height and the
    // target's hitbox center (crouching, a half-block of terrain, a Bedrock hitbox-height
    // mismatch via Geyser) swings the 3D "direction to target" vector sharply up or down, which
    // reads as a huge angle even though the attacker is looking straight at their opponent on
    // screen. This is exactly the false-positive the server owner reproduced: two players
    // colliding in melee range showing dot as low as -0.75 (138 degrees) while clearly fighting
    // face to face. Below this range, the check is skipped entirely rather than tuned around —
    // there is no dot threshold that's both safe at point-blank range and still catches a real
    // 90+ degree "hit without turning" cheat at normal combat distance.
    private static final double MIN_RELIABLE_DISTANCE = 1.4;

    // Ping compensation, same root cause as ReachA (see that class's comment for the full
    // explanation): the server's snapshot of the target's position is stale by roughly the
    // target's own round-trip time, and a target actively dodging/circling during that window
    // can have moved enough that the attacker's genuinely-on-screen aim reads as a much larger
    // angle by the time the server processes the hit. A confirmed 100%-legitimate PvP exchange
    // showed dot as low as -0.94 (160 degrees) — effectively "hit them in the back" — during
    // ordinary circling combat. This loosens the required dot (shifts the threshold toward -1,
    // i.e. more permissive) proportional to the TARGET's ping specifically, since it's the
    // target's movement staleness that matters for this check, not the attacker's.
    private static final double PING_DOT_RELAXATION_PER_100MS = 0.35;
    private static final double PING_DOT_RELAXATION_CAP = 0.9;

    public AimAngleA(ModuleTuning tuning) {
        this.tuning = tuning;
    }

    @Override
    public String name() {
        return "AimAngleA";
    }

    @Override
    public String check(Player player, LivingEntity target, CombatContext context) {
        if (context.distanceToCenter() < MIN_RELIABLE_DISTANCE) {
            return null;
        }

        // Horizontal-only (yaw) angle, rather than the full 3D look-dot. The 3D vector is
        // dominated by vertical noise (eye height vs. hitbox center, crouching, terrain,
        // Bedrock/Geyser hitbox differences) that has nothing to do with whether the attacker
        // was actually looking at their target left/right — which is what a genuine "hit
        // without turning" cheat signature actually looks like. Flattening both vectors onto
        // the XZ plane before comparing removes that vertical noise entirely.
        Vector look = context.eye().getDirection().clone();
        look.setY(0);
        if (look.lengthSquared() < 1.0e-6) return null; // looking straight up/down — no reliable yaw
        look.normalize();

        Vector toTarget = context.targetCenter().toVector().subtract(context.eye().toVector());
        toTarget.setY(0);
        if (toTarget.lengthSquared() < 1.0e-6) return null; // target directly above/below — no reliable yaw
        toTarget.normalize();

        double dot = look.dot(toTarget);
        double effectiveMinDot = tuning.aimAngleMinDot() - pingRelaxation(target);
        if (dot < effectiveMinDot) {
            double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
            return String.format("Out-of-view attack (horizontal dot=%.2f, %.1f deg)", dot, angle);
        }
        return null;
    }

    private double pingRelaxation(LivingEntity target) {
        if (!(target instanceof Player targetPlayer)) return 0.0;
        int ping = targetPlayer.getPing();
        if (ping <= 0) return 0.0;
        double relaxation = (ping / 100.0) * PING_DOT_RELAXATION_PER_100MS;
        return Math.min(relaxation, PING_DOT_RELAXATION_CAP);
    }

    @Override
    public boolean cancelHit() {
        // Deliberately NOT cancelling the hit anymore. Even with ping compensation above, close
        // strafing combat (circling an opponent, both players actively moving) can still produce
        // occasional large horizontal-angle readings purely from network staleness — a real
        // killaura shows this pattern CONSISTENTLY across many hits (which still accumulates VL
        // and can still lead to punishment), whereas an occasional reading during legitimate
        // circling should not cost the player their actual hit. Blocking the hit outright on a
        // single reading was costing legitimate players real PvP exchanges.
        return false;
    }
}
