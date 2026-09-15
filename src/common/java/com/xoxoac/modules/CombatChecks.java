package com.xoxoac.modules;

import com.xoxoac.config.ModuleTuning;
import com.xoxoac.modules.combat.AimAngleA;
import com.xoxoac.modules.combat.AutoClickerA;
import com.xoxoac.modules.combat.CombatCheck;
import com.xoxoac.modules.combat.CombatContext;
import com.xoxoac.modules.combat.MultiAuraA;
import com.xoxoac.modules.combat.NoWallA;
import com.xoxoac.modules.combat.ReachA;
import com.xoxoac.modules.combat.RotationLockA;
import com.xoxoac.modules.movement.BoostedMovement;
import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;

public class CombatChecks implements Listener {

    private final ViolationManager manager;
    private final ModuleTuning tuning;
    // NOTE: swing/click recording (handleSwing) is fed exclusively by PacketClickListener (raw
    // ANIMATION packet via PacketEvents), which also drives the CPS-limit punishment directly —
    // see AutoClickerA/PacketClickListener. This class only reads autoClicker.clear() on quit.
    private final AutoClickerA autoClicker;
    private final BoostedMovement boostedMovement;
    private final List<CombatCheck> hitChecks;

    public CombatChecks(ViolationManager manager, ModuleTuning tuning, AutoClickerA autoClicker,
                         BoostedMovement boostedMovement) {
        this.manager = manager;
        this.tuning = tuning;
        this.autoClicker = autoClicker;
        this.boostedMovement = boostedMovement;
        this.hitChecks = List.of(
                new ReachA(tuning),
                new NoWallA(),
                new AimAngleA(tuning),
                new MultiAuraA(),
                new RotationLockA()
        );
    }

    public int checkCount() {
        return 1 + hitChecks.size();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        autoClicker.clear(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        // IMPORTANT: this now runs at HIGH priority, not MONITOR. MONITOR is documented/intended
        // for observation only — by the time it fires, damage has generally already been applied,
        // so calling event.setCancelled(true) from MONITOR does NOT reliably prevent the hit (this
        // is a well-known Bukkit gotcha, not specific to this plugin). Since ReachA/NoWallA/etc.
        // are meant to actually PREVENT an illegitimate hit — not just log it after the fact —
        // this listener must run before damage is resolved. HIGH still lets most other gameplay
        // plugins (regions, PvP toggles, etc.) run first and potentially cancel the event for
        // their own reasons, which ignoreCancelled=true then correctly respects.
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (shouldSkip(player)) return;

        CombatContext context = CombatContext.from(player, target, System.currentTimeMillis());

        // ROOT CAUSE FOUND: per the official Minecraft Wiki ("Lunge" enchantment), Lunge fires on
        // a normal JAB attack (regular left-click hit) — NOT on a held right-click/"charge". Every
        // hit landed with a spear is a potential Lunge trigger, full stop; there is no separate
        // "charging" state to detect. The previous isChargingSpear()/isHandRaised() check was
        // looking for the wrong trigger entirely, which is why the dash kept slipping past the
        // exemption and showing up as SpeedA false positives during ordinary spear combat.
        boolean isSpear = isHoldingSpear(player);
        if (isSpear) {
            // Arm the shared BoostedMovement grace window on every spear hit — Lunge's dash
            // distance (up to 1.374 bpt at level III per the wiki) would otherwise blow straight
            // through SpeedA's normal walk/sprint cap.
            boostedMovement.markSpearAttack(player);
        }

        // Riptide flight (trident thrown in rain/water) launches the player ballistically in
        // whatever direction the throw imparted — their look direction/rotation during that arc
        // has no bearing on where they're travelling, so aim-angle and rotation-lock checks
        // produce false positives for completely legitimate riptide combat. Same swing-packet
        // gap as the spear applies here too (Riptide's own attack timing doesn't line up neatly
        // with a prior ANIMATION packet either).
        boolean riptiding = player.isRiptiding();

        boolean targetIsPlayer = target instanceof Player;

        for (CombatCheck check : hitChecks) {
            if (!targetIsPlayer && !check.appliesToMobs()) {
                continue;
            }
            if (riptiding && (check instanceof AimAngleA
                    || check instanceof RotationLockA)) {
                continue;
            }

            String details = check.check(player, target, context);
            if (details != null) {
                manager.flag(player, check.name(), details);
                if (check.cancelHit()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    /** Whether the player is currently holding a spear in their main hand. */
    private boolean isHoldingSpear(Player player) {
        var mainHand = player.getInventory().getItemInMainHand();
        return mainHand.getType().name().endsWith("_SPEAR");
    }

    private boolean shouldSkip(Player player) {
        return player.getGameMode() == GameMode.CREATIVE || manager.isExcepted(player);
    }
}
