package com.xoxoac.modules.combat;

import com.xoxoac.config.ModuleTuning;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class ReachA implements CombatCheck {

    // HITBOX FIX: this check compares against context.distanceToHitbox() — the closest point on
    // the target's real BoundingBox — rather than a fixed "center" point. This was changed after
    // confirmed false ReachA flags (up to bans) on players fighting the Ender Dragon: the
    // dragon's hitbox anchor point sits far from much of its actual collidable model (wings,
    // neck, tail), so a legitimate hit on the visible model could read as 10+ blocks from the
    // anchor-derived center while the player was standing right next to the real hitbox. See
    // CombatContext for the full explanation.
    //
    // Ping compensation. THE ROOT CAUSE THIS FIXES: distanceToCenter() is measured between
    // player.getEyeLocation() and target.getLocation() at the moment the SERVER processes
    // EntityDamageByEntityEvent — which is always after both the attacker's client-side click
    // AND however long it took that hit to reach the server. In real PvP, both players are
    // actively moving (circling, closing distance, retreating) the whole time, so the server's
    // snapshot of "where the target is right now" is measurably stale by the time it's compared
    // against "where the attacker's client thought the target was when they clicked" — and that
    // staleness scales directly with ping. A completely legitimate melee exchange at 100-150ms
    // ping will routinely show 0.2-0.5 extra blocks of apparent reach for exactly this reason;
    // this was confirmed by a server owner report of "reach 4.6-4.9, sometimes 6.0+" during
    // admittedly 100% legitimate PvP. A flat, ping-blind leniency constant cannot be tuned to
    // both catch real reach cheats AND not punish ordinary players with ordinary ping — one
    // number can't serve two different physical situations.
    //
    // This mirrors what every serious anticheat does (Vulcan, Matrix, Forge's own canReach():
    // "additional padding is added to account for movement/lag"). Both players' ping matters —
    // the attacker's ping delays how stale their own view of the target is, and the target's own
    // recent movement (itself a function of their ping/tick timing) contributes too — so this
    // uses the higher of the two ping values as a simple, conservative proxy for total staleness
    // rather than trying to model exact packet timing.
    //
    // The extra allowance is capped (PING_COMPENSATION_CAP_BLOCKS) specifically so a cheater
    // can't just fake a huge reported ping to buy themselves unlimited extra reach — real reach
    // hacks push distances far beyond what even a very bad connection explains.
    private static final double PING_COMPENSATION_PER_100MS = 0.35;
    private static final double PING_COMPENSATION_CAP_BLOCKS = 1.2;

    // Velocity compensation, same root cause as ping compensation above but for a different
    // dimension of it: at elytra-flight speeds (which can exceed 10+ blocks/sec with firework
    // boosting), even a modest ping's worth of position staleness translates into a MUCH larger
    // apparent reach error than the same ping would produce for a walking/running player — a
    // fixed ping-based cap alone cannot account for this. This was confirmed by a report of
    // hits simply not registering at all during elytra PvP, with reach readings up to 7.11
    // blocks (vs. the ~4.6-4.9 typical of ground PvP ping staleness) despite the attacker
    // genuinely landing the hit on their own screen. This adds an allowance proportional to how
    // fast the TARGET is moving (their motion during the network round-trip is what actually
    // displaces them from where the attacker's client saw them), separately capped so a
    // stationary-looking but artificially-reported "fast" target can't be abused for unlimited
    // reach.
    private static final double VELOCITY_COMPENSATION_PER_BLOCK_PER_SEC = 0.15;
    private static final double VELOCITY_COMPENSATION_CAP_BLOCKS = 2.5;

    private final ModuleTuning tuning;

    public ReachA(ModuleTuning tuning) {
        this.tuning = tuning;
    }

    @Override
    public String name() {
        return "ReachA";
    }

    @Override
    public String check(Player player, LivingEntity target, CombatContext context) {
        double baseReach = isHoldingSpear(player) ? tuning.reachMaxBlocksSpear() : tuning.reachMaxBlocks();
        double maxDistance = baseReach + tuning.reachLeniency()
                + pingCompensation(player, target)
                + velocityCompensation(player, target);
        if (context.distanceToHitbox() > maxDistance) {
            return String.format("Combat reach %.2f > %.2f blocks", context.distanceToHitbox(), maxDistance);
        }
        return null;
    }

    private double velocityCompensation(Player player, LivingEntity target) {
        double attackerSpeed = player.getVelocity().length();
        double targetSpeed = target.getVelocity().length();
        // The higher of the two matters: either one moving fast enough (an elytra-flying
        // attacker chasing a stationary target, or vice-versa, or both) displaces the true
        // relative position by roughly the same amount over the same network round-trip.
        double relevantSpeedBlocksPerTick = Math.max(attackerSpeed, targetSpeed);
        double relevantSpeedBlocksPerSec = relevantSpeedBlocksPerTick * 20.0; // 20 ticks/sec

        double compensation = relevantSpeedBlocksPerSec * VELOCITY_COMPENSATION_PER_BLOCK_PER_SEC;
        return Math.min(compensation, VELOCITY_COMPENSATION_CAP_BLOCKS);
    }

    private double pingCompensation(Player player, LivingEntity target) {
        int attackerPing = player.getPing();
        int targetPing = target instanceof Player targetPlayer ? targetPlayer.getPing() : 0;
        int worstPing = Math.max(attackerPing, targetPing);
        if (worstPing <= 0) return 0.0;

        double compensation = (worstPing / 100.0) * PING_COMPENSATION_PER_100MS;
        return Math.min(compensation, PING_COMPENSATION_CAP_BLOCKS);
    }

    @Override
    public boolean cancelHit() {
        // Deliberately NOT cancelling the hit anymore, for the same reason as AimAngleA/NoWallA:
        // even with ping AND velocity compensation above, this was still confirmed to be
        // blocking real, on-screen-landed hits during elytra PvP ("удары не регают ваще"). A
        // real reach hack shows this pattern consistently across many hits regardless of
        // movement speed — that still accumulates VL and can still lead to punishment (kick,
        // escalating to a ban on a 3rd occurrence within 24h — see ViolationManager) — whereas an
        // occasional reading during fast, legitimate combat should not cost the player their
        // actual hit.
        return false;
    }

    @Override
    public boolean appliesToMobs() {
        // Reach is physically meaningless regardless of target type — you can't legitimately
        // hit a zombie (or a player) from further away than your weapon's actual range either.
        return true;
    }

    private boolean isHoldingSpear(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        return mainHand.getType().name().endsWith("_SPEAR");
    }
}
