package com.xoxoac.modules.combat;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

public interface CombatCheck {

    String name();

    String check(Player player, LivingEntity target, CombatContext context);

    default boolean cancelHit() {
        return false;
    }

    /**
     * Whether this check should run against non-player targets (mobs, animals, etc.) at all.
     * Defaults to PLAYERS-ONLY (false) — most combat checks here (MultiAuraA, RotationLockA,
     * AimAngleA) exist to catch PvP-specific cheat signatures (killaura swapping targets,
     * a bot not adjusting its camera between hits, attacking without looking at the target) that
     * simply don't apply to farming mobs: standing still and clicking through a mob spawner/farm
     * queue is completely legitimate play and will naturally "fail" those same signatures.
     * Checks whose logic is physically meaningless regardless of target type (ReachA, NoWallA —
     * you can't hit something farther than your reach or through a wall no matter what it is)
     * override this to true.
     */
    default boolean appliesToMobs() {
        return false;
    }
}
