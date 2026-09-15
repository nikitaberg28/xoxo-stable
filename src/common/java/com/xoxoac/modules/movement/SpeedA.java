package com.xoxoac.modules.movement;

import com.xoxoac.config.ModuleTuning;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SpeedA implements MovementCheck {

    private final ModuleTuning tuning;

    // ROOT CAUSE (v2): player.isSprinting() on Bukkit reflects the Java sprinting metadata flag,
    // which GeyserMC does not always keep in perfect sync with a Bedrock client's actual
    // movement — the flag can read false for a tick or two right around a jump even though the
    // player's real, client-side speed still carries the full sprint-jump momentum (confirmed by
    // /xoxo debug traces: dY following the exact vanilla 0.42 jump-impulse curve, i.e. definitely
    // a real jump, while isSprinting()==false at the exact tick SpeedA flagged). Relying purely
    // on the instantaneous flag was therefore still catching ordinary sprint-jumps whenever that
    // desync happened to line up with the peak-speed tick.
    //
    // Fix: remember "was sprinting recently" for a short window, the same pattern already used
    // for Riptide/spear-Lunge in BoostedMovement, so a momentary flag desync doesn't undo the
    // sprint-jump exemption.
    private static final int RECENT_SPRINT_TICKS = 10;
    private final Map<UUID, Integer> recentSprintTicks = new HashMap<>();

    public SpeedA(ModuleTuning tuning) {
        this.tuning = tuning;
    }

    @Override
    public String name() {
        return "SpeedA";
    }

    @Override
    public String check(Player player, MovementContext context) {
        // Riptide and spear-Lunge dash bursts are legitimate high-speed vanilla mechanics.
        if (player.isRiptiding() || context.boosted()) return null;

        UUID uuid = player.getUniqueId();
        if (player.isSprinting()) {
            recentSprintTicks.put(uuid, RECENT_SPRINT_TICKS);
        }
        int recentSprint = recentSprintTicks.getOrDefault(uuid, 0);
        boolean sprintingRecently = recentSprint > 0;
        if (recentSprint > 0) {
            recentSprintTicks.put(uuid, recentSprint - 1);
        }

        double maxSpeed = sprintingRecently ? tuning.speedSprintMaxBpt() : tuning.speedMaxBpt();

        // Sprint-jumping's extra acceleration bonus is strongest for the first few ticks after
        // leaving the ground, then decays as air drag takes over — give a wider cap specifically
        // during that early-airborne window instead of only ever using the flat sprint cap.
        if (sprintingRecently && context.airTicks() <= tuning.speedSprintJumpGraceTicks()) {
            maxSpeed = tuning.speedSprintJumpMaxBpt();
        }

        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            maxSpeed += (player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1) * tuning.speedPotionBonusPerLevel();
        }

        if (context.deltaXZ() > maxSpeed) {
            return "Speed exceeded: " + String.format("%.2f", context.deltaXZ())
                    + " bpt (max " + String.format("%.2f", maxSpeed) + ")";
        }
        return null;
    }

    public void clear(UUID uuid) {
        recentSprintTicks.remove(uuid);
    }
}
