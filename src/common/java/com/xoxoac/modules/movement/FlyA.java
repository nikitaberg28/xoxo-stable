package com.xoxoac.modules.movement;

import com.xoxoac.config.ModuleTuning;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class FlyA implements MovementCheck {

    private final ModuleTuning tuning;

    public FlyA(ModuleTuning tuning) {
        this.tuning = tuning;
    }

    @Override
    public String name() {
        return "FlyA";
    }

    @Override
    public String check(Player player, MovementContext context) {
        // 1. Core Riptide Speed Protection
        if (player.isRiptiding()) {
            double maxRiptideY = tuning.flyMaxRiptideY();
            if (context.deltaY() > maxRiptideY) {
                return String.format("Riptide speed too high: %.2f bpt (max %.2f)",
                        context.deltaY(), maxRiptideY);
            }
            return null;
        }

        // 1b. Spear "Lunge" dash burst — same shape as Riptide, not tied to water.
        if (context.boosted()) {
            return null;
        }

        // 2. Water Exit Exemption
        // Checks feet, one block up (roughly chest/head height) and one block down — a player
        // mid-way through surfacing from a water column (e.g. swimming straight up out of a
        // ravine) can have their feet already clear of liquid while their upper body is still
        // in it, or vice-versa for the block directly below; checking only the feet block missed
        // that partially-submerged moment and is what let residual swim-out momentum get
        // misread as unsupported ascent right as the WATER_EXIT_GRACE window (see MovementChecks)
        // was also running out.
        Location loc = player.getLocation();
        boolean nearWater = loc.getBlock().getType() == Material.WATER
                || loc.clone().add(0, 1, 0).getBlock().getType() == Material.WATER
                || loc.clone().add(0, -1, 0).getBlock().getType() == Material.WATER;

        if (nearWater) {
            return null;
        }

        // 3. Physical Flight Checks (Gravity and Air Logic Only)
        // Small epsilon instead of a strict > 0 / == 0.0 comparison — knockback decay and other
        // vanilla physics can leave tiny residual vertical motion (e.g. 0.01-0.02) that isn't
        // actual flight, and exact-equality float comparisons are fragile in general.
        //
        // Vanilla step-up exemption: walking onto a stair/slab/carpet/snow-layer moves the
        // player's Y position by up to 0.6 blocks in a single resolved step (Minecraft's own
        // "stepping" mechanic — see the Minecraft Parkour Wiki's technical breakdown: "maximum
        // step height is 0.6b... step up blocks like carpets, slabs, and even beds"). This is
        // NOT jump physics and can legitimately produce a single-tick deltaY of up to ~0.6 even
        // while airTicks happens to be elevated from an unrelated recent moment (e.g. a tick or
        // two of genuine non-ground contact during the step transition itself, or residual
        // Bedrock/Geyser movement smoothing near the step's edge). A real flight hack sustains
        // ascent over MANY ticks, not a single bounded step-height jump — so any single-tick
        // ascent within the vanilla step envelope is exempted outright, regardless of airTicks.
        if (context.deltaY() > 0.03 && context.deltaY() <= MAX_VANILLA_STEP_HEIGHT) {
            return null;
        }

        if (context.airTicks() > tuning.flyAirTicksBeforeFlag()) {
            if (context.deltaY() > MAX_VANILLA_STEP_HEIGHT) {
                return "Ascending without support (Gravity Defiance)";
            }
            if (Math.abs(context.deltaY()) < 0.001 && !context.clientGround()) {
                return "Hovering mid-air (Zero vertical motion)";
            }
        }

        return null;
    }

    // Vanilla's own step-up ceiling — see the comment above. A hair above 0.6 to give a small
    // amount of floating-point slack rather than rejecting a step at exactly the boundary.
    private static final double MAX_VANILLA_STEP_HEIGHT = 0.62;
}
