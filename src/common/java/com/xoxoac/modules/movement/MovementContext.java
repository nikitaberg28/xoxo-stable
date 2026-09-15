package com.xoxoac.modules.movement;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

public final class MovementContext {

    private final Location from;
    private final Location to;
    private final double deltaY;
    private final double deltaXZ;
    private final boolean serverGround;
    private final boolean clientGround;
    private final boolean onLiquid;
    private final int airTicks;
    private final double currentVelocityY;
    private final double naturalJumpVelocity;
    private final boolean boosted;

    private MovementContext(
            Location from,
            Location to,
            boolean serverGround,
            boolean clientGround,
            boolean onLiquid,
            int airTicks,
            double currentVelocityY,
            double naturalJumpVelocity,
            boolean boosted
    ) {
        this.from = from.clone();
        this.to = to.clone();
        this.deltaY = to.getY() - from.getY();
        this.deltaXZ = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        this.serverGround = serverGround;
        this.clientGround = clientGround;
        this.onLiquid = onLiquid;
        this.airTicks = airTicks;
        this.currentVelocityY = currentVelocityY;
        this.naturalJumpVelocity = naturalJumpVelocity;
        this.boosted = boosted;
    }

    public static MovementContext from(
            Player player,
            Location from,
            Location to,
            boolean serverGround,
            boolean clientGround,
            boolean onLiquid,
            int airTicks,
            double currentVelocityY,
            double naturalJumpVelocity,
            boolean boosted
    ) {
        return new MovementContext(
                from,
                to,
                serverGround,
                clientGround,
                onLiquid,
                airTicks,
                currentVelocityY,
                naturalJumpVelocity,
                boosted
        );
    }

    /** True if this tick's motion looks like a legitimate Riptide or spear-Lunge dash burst. */
    public boolean boosted() {
        return boosted;
    }

    public static double naturalJumpVelocity(Player player) {
        double velocity = 0.42;
        if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
            velocity += 0.1 * (player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier() + 1);
        }
        return velocity;
    }

    public Location from() {
        return from.clone();
    }

    public Location to() {
        return to.clone();
    }

    public double deltaY() {
        return deltaY;
    }

    public double deltaXZ() {
        return deltaXZ;
    }

    public boolean serverGround() {
        return serverGround;
    }

    public boolean clientGround() {
        return clientGround;
    }

    public boolean onLiquid() {
        return onLiquid;
    }

    public int airTicks() {
        return airTicks;
    }

    public double currentVelocityY() {
        return currentVelocityY;
    }

    public double naturalJumpVelocity() {
        return naturalJumpVelocity;
    }
}
