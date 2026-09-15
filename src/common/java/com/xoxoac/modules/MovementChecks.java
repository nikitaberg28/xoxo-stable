package com.xoxoac.modules;

import com.xoxoac.config.ModuleTuning;
import com.xoxoac.core.BedrockPlayers;
import com.xoxoac.core.MovementTracer;
import com.xoxoac.modules.movement.BoostedMovement;
import com.xoxoac.modules.movement.FlyA;
import com.xoxoac.modules.movement.FlyB;
import com.xoxoac.modules.movement.JumpA;
import com.xoxoac.modules.movement.MovementCheck;
import com.xoxoac.modules.movement.MovementContext;
import com.xoxoac.modules.movement.SpeedA;
import com.xoxoac.modules.movement.StepA;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MovementChecks implements Listener {

    // Was 10 ticks (0.5s) — too short. Landing from an elytra flight (especially a long, fast
    // firework-boosted glide) and immediately swapping the elytra for a chestplate leaves real
    // residual horizontal momentum that easily outlasts half a second — reported as SpeedA
    // false-flagging on nearly every elytra-to-chestplate swap ("1 time per swap, not cyclic",
    // stopping a precisely-calculated landing and turning it into a straight drop instead).
    // 40 ticks (2s) gives that momentum realistic time to decay under normal air resistance
    // before SpeedA starts judging horizontal speed again.
    private static final int ELYTRA_GRACE = 40;
    private static final int KNOCKBACK_GRACE = 40;
    // Was 8 ticks (~0.4s) — too short. A player rising up a long water column (climbing out of
    // a ravine/well by swimming straight up) carries real residual vertical momentum from that
    // ascent that easily outlasts 0.4s once their head actually breaks the surface — this was
    // the exact "поднятии с ущелья по воде начало флагать" report (buffer climbing from 4 to 21
    // over ~3 seconds straight after a water exit). 25 ticks (~1.25s) gives a legitimate swim-out
    // enough time for that momentum to naturally decay under gravity before FlyA starts judging
    // "ascending without support" again.
    private static final int WATER_EXIT_GRACE = 25;

    private static final Set<Material> CLIMBABLE = Set.of(
            Material.LADDER,
            Material.VINE,
            Material.TWISTING_VINES,
            Material.WEEPING_VINES,
            Material.TWISTING_VINES_PLANT,
            Material.WEEPING_VINES_PLANT,
            Material.CAVE_VINES,
            Material.CAVE_VINES_PLANT,
            Material.SCAFFOLDING
    );

    private final ViolationManager manager;
    private final ModuleTuning tuning;
    private final List<MovementCheck> checks;
    private final SpeedA speedA;

    private final BoostedMovement boostedMovement;
    private final MovementTracer tracer;
    private final Map<UUID, Integer> airTicks = new HashMap<>();
    private final Map<UUID, Integer> elytraGraceTicks = new HashMap<>();
    private final Map<UUID, Integer> knockbackGrace = new HashMap<>();
    private final Map<UUID, Integer> waterExitGraceTicks = new HashMap<>();
    private final Map<UUID, Boolean> wasInLiquid = new HashMap<>();
    private final Map<UUID, Boolean> levitating = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> buffers = new HashMap<>();

    public MovementChecks(ViolationManager manager, ModuleTuning tuning, BoostedMovement boostedMovement, MovementTracer tracer) {
        this.manager = manager;
        this.tuning = tuning;
        this.boostedMovement = boostedMovement;
        this.tracer = tracer;
        this.speedA = new SpeedA(tuning);
        this.checks = List.of(
                new StepA(),
                new FlyA(tuning),
                new FlyB(),
                new JumpA(),
                speedA
        );
    }

    public int checkCount() {
        return checks.size();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        if (event.isCancelled()) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (manager.isExcepted(player)) return;

        double velY = event.getVelocity().getY();
        double naturalMax = MovementContext.naturalJumpVelocity(player);

        if (velY > naturalMax + 0.25) {
            knockbackGrace.put(uuid, KNOCKBACK_GRACE);
            airTicks.put(uuid, 0);
        } else if (velY < 0.1 && event.getVelocity().length() > 0.5) {
            knockbackGrace.put(uuid, KNOCKBACK_GRACE / 2);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (manager.isExcepted(player)) return;

        switch (event.getCause()) {
            case PROJECTILE,
                 ENTITY_ATTACK,
                 ENTITY_EXPLOSION,
                 BLOCK_EXPLOSION,
                 FLY_INTO_WALL -> knockbackGrace.put(player.getUniqueId(), KNOCKBACK_GRACE / 2);
            default -> {
            }
        }
    }

    @EventHandler
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();

        switch (event.getAction()) {
            case ADDED, CHANGED -> {
                if (event.getNewEffect() != null
                        && event.getNewEffect().getType().equals(PotionEffectType.LEVITATION)) {
                    levitating.put(uuid, true);
                }
            }
            case REMOVED, CLEARED -> {
                if (event.getOldEffect() != null
                        && event.getOldEffect().getType().equals(PotionEffectType.LEVITATION)) {
                    levitating.put(uuid, false);
                }
            }
            default -> {
            }
        }
    }

    /**
     * Bedrock-specific Riptide detection — see BoostedMovement's v5 Javadoc note for the full
     * explanation. A Bedrock/Geyser client can launch a Riptide throw (barrier-block-over-rain
     * situations) that never sets player.isRiptiding() true on the Java server side, because the
     * server's own rain-exposure check correctly disagrees with what the Bedrock client visually
     * shows. Arming the same grace window directly off the right-click interaction — gated on
     * "confirmed Bedrock client" + "actually holding a Riptide-enchanted trident in either hand"
     * — closes that gap without loosening anything for Java players (whose isRiptiding() flag is
     * trustworthy on its own) or for Bedrock players who aren't using Riptide at all.
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (manager.isExcepted(player)) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!BedrockPlayers.isBedrock(player)) return;

        ItemStack item = event.getHand() == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();

        // Riptide can be enchanted onto the vanilla trident (Material.TRIDENT), NOT the "_SPEAR"
        // custom weapons used elsewhere in this plugin (ReachA/BoostedMovement's spear-Lunge
        // handling) — those are a different item family with their own separate dash mechanic.
        if (item.getType() == Material.TRIDENT
                && item.getEnchantments().containsKey(Enchantment.RIPTIDE)) {
            boostedMovement.markPossibleBedrockRiptide(player);
        }
    }

    @EventHandler
    public void onGlideToggle(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (manager.isExcepted(player)) return;

        UUID uuid = player.getUniqueId();
        if (!event.isGliding()) {
            elytraGraceTicks.put(uuid, ELYTRA_GRACE);
        }
        airTicks.put(uuid, 0);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (shouldSkip(player)) return;

        if (levitating.getOrDefault(uuid, false) || player.hasPotionEffect(PotionEffectType.LEVITATION)) {
            reduceAll(uuid);
            return;
        }

        if (player.isGliding()) {
            airTicks.put(uuid, 0);
            reduceAll(uuid);
            return;
        }

        int elytraGrace = elytraGraceTicks.getOrDefault(uuid, 0);
        if (elytraGrace > 0) {
            elytraGraceTicks.put(uuid, elytraGrace - 1);
            airTicks.put(uuid, 0);
            reduceAll(uuid);
            return;
        }

        double currentVelY = player.getVelocity().getY();
        double naturalMax = MovementContext.naturalJumpVelocity(player);
        if (currentVelY > naturalMax + 0.15) {
            airTicks.put(uuid, 0);
            reduceAll(uuid);
            return;
        }

        int kbGrace = knockbackGrace.getOrDefault(uuid, 0);
        if (kbGrace > 0) {
            knockbackGrace.put(uuid, kbGrace - 1);
            if (kbGrace == 1) airTicks.put(uuid, 0);
            reduceAll(uuid);
            return;
        }

        int waterExitGrace = waterExitGraceTicks.getOrDefault(uuid, 0);
        if (waterExitGrace > 0) {
            waterExitGraceTicks.put(uuid, waterExitGrace - 1);
            airTicks.put(uuid, 0);
            reduceAll(uuid);
            return;
        }

        Location to = event.getTo();
        if (to == null || event.getFrom().getWorld() != to.getWorld()) return;

        boolean serverGround = isActuallyOnGround(player.getLocation());
        boolean onClimbable = isOnClimbable(player.getLocation());
        boolean currentlyInLiquid = player.getLocation().getBlock().isLiquid();

        // Track water exit for grace period
        boolean previouslyInLiquid = wasInLiquid.getOrDefault(uuid, false);
        if (previouslyInLiquid && !currentlyInLiquid) {
            waterExitGraceTicks.put(uuid, WATER_EXIT_GRACE);
        }
        wasInLiquid.put(uuid, currentlyInLiquid);

        if (currentlyInLiquid) {
            reduceAll(uuid);
            return;
        }

        if (onClimbable) {
            airTicks.put(uuid, 0);
            reduceAll(uuid);
            return;
        }

        if (serverGround) {
            // Distinguish a vanilla "step-up" (walking onto a slab/stair/carpet, max 0.6 blocks,
            // per Minecraft's stepping mechanic) from genuine unsupported ascent. Stepping moves
            // the player's Y position in a single tick via block-collision resolution, NOT normal
            // jump physics — the player is on solid ground both the tick before and the tick
            // after, with a step-sized Y jump in between. Without this, repeatedly walking up
            // stairs/slabs/carpets (e.g. climbing a staircase) could accumulate airTicks/trigger
            // FlyA's "Ascending without support" on the step transition itself, which is exactly
            // what was reported ("просто прыгая по ступеням и полублокам"). A read on airTicks
            // BEFORE this tick's update below tells us whether the player was already considered
            // grounded going into this tick.
            airTicks.put(uuid, 0);
        } else {
            airTicks.put(uuid, airTicks.getOrDefault(uuid, 0) + 1);
        }

        double deltaYThisTick = to.getY() - event.getFrom().getY();
        double deltaXZThisTick = Math.hypot(to.getX() - event.getFrom().getX(), to.getZ() - event.getFrom().getZ());
        boolean boosted = boostedMovement.isBoostedThisTick(player, deltaYThisTick, deltaXZThisTick);

        tracer.trace(player, "move", deltaYThisTick, deltaXZThisTick, boosted,
                airTicks.getOrDefault(uuid, 0), boostedMovement.graceRemaining(uuid));

        MovementContext context = MovementContext.from(
                player,
                event.getFrom(),
                to,
                serverGround,
                player.isOnGround(),
                player.getLocation().clone().subtract(0, 0.1, 0).getBlock().isLiquid(),
                airTicks.getOrDefault(uuid, 0),
                currentVelY,
                naturalMax,
                boosted
        );

        if (boosted) {
            // Legitimate Riptide / spear-Lunge dash burst — let the movement through untouched
            // and don't build up any check buffers for it.
            airTicks.put(uuid, 0);
            reduceAll(uuid);
            return;
        }

        boolean anyFailed = false;
        for (MovementCheck check : checks) {
            String details = check.check(player, context);
            if (details != null) {
                anyFailed = true;
                handleViolation(player, event, check.name(), details);
            } else {
                reduce(uuid, check.name());
            }
        }

        if (!anyFailed && serverGround) {
            reduceAll(uuid);
        }
    }

    @EventHandler
    public void onBoatMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Boat boat)) return;
        if (!(boat.getPassenger() instanceof Player player)) return;
        if (manager.isExcepted(player)) return;

        double deltaY = event.getTo().getY() - event.getFrom().getY();
        Block blockBelow = boat.getLocation().subtract(0, 0.5, 0).getBlock();

        if (deltaY > 0.5 && !boat.isInWater() && !blockBelow.getType().isSolid()) {
            manager.flag(player, "BoatFlyA", "Boat ascending in air");
            boat.setVelocity(new org.bukkit.util.Vector(0, -0.5, 0));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        airTicks.remove(uuid);
        elytraGraceTicks.remove(uuid);
        knockbackGrace.remove(uuid);
        waterExitGraceTicks.remove(uuid);
        wasInLiquid.remove(uuid);
        levitating.remove(uuid);
        buffers.remove(uuid);
        boostedMovement.clear(uuid);
        speedA.clear(uuid);
    }

    private boolean shouldSkip(Player player) {
        return manager.isExcepted(player)
                || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR
                || player.isSwimming()
                // Passenger of any vehicle/entity (boat, happy ghast, horse, minecart, etc.) —
                // the mount's own physics/animations produce Y motion the rider doesn't control,
                // so movement checks must not run against the passenger at all.
                || player.isInsideVehicle()
                // A legitimate /fly (Essentials or vanilla creative-style flight) is not a hack;
                // Bukkit exposes this directly instead of us having to guess from motion patterns.
                || player.isFlying()
                || player.getAllowFlight();
    }

    private void handleViolation(Player player, PlayerMoveEvent event, String check, String info) {
        UUID uuid = player.getUniqueId();
        int vl = buffers.computeIfAbsent(uuid, key -> new HashMap<>())
                .merge(check, 1, Integer::sum);

        if (vl > tuning.movementBufferToFlag()) {
            tracer.trace(player, "FLAG:" + check, event.getTo().getY() - event.getFrom().getY(),
                    Math.hypot(event.getTo().getX() - event.getFrom().getX(), event.getTo().getZ() - event.getFrom().getZ()),
                    boostedMovement.graceRemaining(uuid) > 0, airTicks.getOrDefault(uuid, 0),
                    boostedMovement.graceRemaining(uuid));
            manager.flag(player, check, info + " (buffer:" + vl + ")");
            event.setTo(event.getFrom());
        }
    }

    private void reduce(UUID uuid, String check) {
        Map<String, Integer> playerBuffers = buffers.get(uuid);
        if (playerBuffers == null) return;
        playerBuffers.computeIfPresent(check, (key, value) -> value <= 1 ? null : value - 1);
    }

    private void reduceAll(UUID uuid) {
        Map<String, Integer> playerBuffers = buffers.get(uuid);
        if (playerBuffers == null) return;
        playerBuffers.replaceAll((check, value) -> Math.max(0, value - 1));
    }

    /**
     * Server-side ground check — deliberately does NOT trust the client's own on-ground flag
     * (Entity#isOnGround()/MovementContext's "clientGround"), since that's client-reported and
     * a modified client can simply lie about it — that's exactly the kind of thing this plugin
     * exists to catch, so blindly trusting it would open a hole rather than close one.
     *
     * Uses each sampled block's actual collision shape (BoundingBox), not just
     * Material#isSolid() — a slab, stair, trapdoor, or similar partial-height block reports
     * "solid" as a material but its true collision volume only occupies PART of its 1x1x1 cell,
     * and a naive isSolid() check doesn't know whether the player's feet are actually within
     * that volume or hovering just above/beside it. That mismatch was the concrete bug behind
     * the "flags FlyA while just running" report: a Bedrock/Geyser player's smoothed movement
     * crossing the edge of a slab/stair/partial block could read as several consecutive "not
     * touching anything" ticks even though they were plainly on solid ground the whole time.
     */
    private boolean isActuallyOnGround(Location loc) {
        if (loc.getWorld() == null) return false;
        double[] xzOffsets = {-0.3, -0.15, 0.0, 0.15, 0.3};

        for (double x : xzOffsets) {
            for (double z : xzOffsets) {
                // Sample a thin vertical slice just below the feet — catches both a full block
                // and a partial-height block (slab/stair/trapdoor) whose collision top sits
                // anywhere in roughly the bottom quarter-block below the player.
                Location sample = loc.clone().add(x, -0.05, z);
                Block block = sample.getBlock();
                if (block.isLiquid()) continue;

                BoundingBox box = block.getBoundingBox();
                if (box.getVolume() <= 0) continue; // no real collision shape here (air, grass, etc.)

                // Both getBoundingBox() and `sample` are in world coordinates already, so no
                // conversion is needed — just test the sampled point directly against the box.
                // The +0.1 second check gives a small amount of vertical slack so a foot resting
                // exactly on a collision surface (floating-point edge case) still counts.
                if (box.contains(sample.getX(), sample.getY(), sample.getZ())
                        || box.contains(sample.getX(), sample.getY() + 0.1, sample.getZ())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isOnClimbable(Location loc) {
        Block feet = loc.getBlock();
        Block head = loc.clone().add(0, 1, 0).getBlock();
        Block below = loc.clone().subtract(0, 0.1, 0).getBlock();
        return CLIMBABLE.contains(feet.getType())
                || CLIMBABLE.contains(head.getType())
                || CLIMBABLE.contains(below.getType());
    }
}
