package com.xoxoac.modules.combat;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public final class CombatContext {

    private final long timestampMs;
    private final Location eye;
    private final Location targetCenter;
    private final double distanceToCenter;
    private final double distanceToHitbox;
    private final double lookDot;

    private CombatContext(long timestampMs, Location eye, Location targetCenter, BoundingBox targetBox) {
        this.timestampMs = timestampMs;
        this.eye = eye.clone();
        this.targetCenter = targetCenter.clone();
        this.distanceToCenter = eye.distance(targetCenter);

        // ROOT CAUSE THIS FIXES: for most mobs the "center" (getLocation() + height/2) is a
        // decent stand-in for "where the hitbox actually is", so distanceToCenter() was close
        // enough to the real reach distance. That assumption breaks badly for entities whose
        // hitbox is huge and/or not centered on their anchor point — the Ender Dragon is the
        // extreme case: its getLocation() anchor sits near one part of the model, but the
        // dragon's actual collidable hitbox (wings, neck, tail) extends many blocks away from
        // that point in every direction. A player legitimately hitting the visible model (e.g. a
        // wing) can be several blocks from the anchor-derived "center" while being right next to
        // the real hitbox — which is exactly what a reach hack also looks like under the old
        // metric, so ReachA couldn't tell them apart and flagged (up to bans) players who were
        // clicking on a dragon that was, physically, in melee range.
        //
        // Fix: measure to the closest point on the entity's actual BoundingBox (its true
        // collidable volume, same source of truth NoWallA already uses for its raytrace) rather
        // than a single fixed point. For normal-sized mobs this is nearly identical to the old
        // center-distance; for huge/irregular hitboxes it reflects the real melee distance.
        Vector eyeVec = eye.toVector();
        Vector closestOnBox = new Vector(
                clamp(eyeVec.getX(), targetBox.getMinX(), targetBox.getMaxX()),
                clamp(eyeVec.getY(), targetBox.getMinY(), targetBox.getMaxY()),
                clamp(eyeVec.getZ(), targetBox.getMinZ(), targetBox.getMaxZ())
        );
        this.distanceToHitbox = eyeVec.distance(closestOnBox);

        Vector look = eye.getDirection().normalize();
        Vector toTarget = targetCenter.toVector().subtract(eye.toVector()).normalize();
        this.lookDot = look.dot(toTarget);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static CombatContext from(Player player, LivingEntity target, long nowMs) {
        Location eye = player.getEyeLocation();
        Location center = target.getLocation().add(0, target.getHeight() / 2.0, 0);
        return new CombatContext(nowMs, eye, center, target.getBoundingBox());
    }

    public long timestampMs() {
        return timestampMs;
    }

    public Location eye() {
        return eye.clone();
    }

    public Location targetCenter() {
        return targetCenter.clone();
    }

    // Kept for any other check (e.g. lookDot above) that wants "roughly where the target's
    // torso is" rather than "closest reachable point" — those are different questions.
    public double distanceToCenter() {
        return distanceToCenter;
    }

    // Use this for reach/range checks: the real, physical melee distance to the target,
    // correct regardless of hitbox size or shape.
    public double distanceToHitbox() {
        return distanceToHitbox;
    }

    public double lookDot() {
        return lookDot;
    }
}
