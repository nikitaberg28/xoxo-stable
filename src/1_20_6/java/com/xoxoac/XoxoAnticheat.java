package com.xoxoac;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import com.xoxoac.config.CheckSettings;
import com.xoxoac.config.Messages;
import com.xoxoac.config.ModuleTuning;
import com.xoxoac.config.Punishments;
import com.xoxoac.core.MovementTracer;
import com.xoxoac.core.PunishmentLogger;
import com.xoxoac.listeners.PacketClickListener;
import com.xoxoac.listeners.PlayerDataListener;
import com.xoxoac.modules.*;
import com.xoxoac.modules.combat.AutoClickerA;
import com.xoxoac.modules.movement.BoostedMovement;
import com.xoxoac.gui.SuspectGui;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class XoxoAnticheat extends JavaPlugin {

    private Messages messages;
    private CheckSettings checkSettings;
    private ModuleTuning moduleTuning;
    private ViolationManager violationManager;
    private AutoClickerA autoClicker;
    private BoostedMovement boostedMovement;
    private MovementTracer movementTracer;
    private CombatChecks combatChecks;
    private PacketClickListener packetClickListener;
    private SuspectGui suspectGui;

    @Override
    public void onLoad() {
        // PacketEvents must be built/loaded in onLoad(), before init(), and packet listeners
        // should be registered here too — see docs.packetevents.com/creating-your-packetevents-instance.
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();

        saveDefaultConfig();

        messages = new Messages(this);
        checkSettings = new CheckSettings(this);
        moduleTuning = new ModuleTuning(this);
        Punishments punishments = new Punishments(this);
        PunishmentLogger punishmentLogger = new PunishmentLogger(this);

        violationManager = new ViolationManager(this, messages, checkSettings, punishments, punishmentLogger);
        movementTracer = new MovementTracer(this);

        // Shared BoostedMovement instance — MovementChecks (movement ticks) and CombatChecks
        // (spear-attack hit events) both need to read/write the SAME grace state, since a spear
        // attack observed by CombatChecks must arm the grace window that MovementChecks then
        // consults on the following movement ticks.
        boostedMovement = new BoostedMovement(moduleTuning);

        // Shared AutoClickerA instance — CombatChecks and PacketClickListener must use the SAME
        // instance. PacketClickListener is the sole writer (records clicks); CombatChecks only
        // clears it on quit, so a physical click is never counted twice.
        autoClicker = new AutoClickerA(moduleTuning);
        combatChecks = new CombatChecks(violationManager, moduleTuning, autoClicker, boostedMovement);

        packetClickListener = new PacketClickListener(this, autoClicker, violationManager);
        PacketEvents.getAPI().getEventManager().registerListener(packetClickListener, PacketListenerPriority.NORMAL);
    }

    @Override
    public void onEnable() {
        PacketEvents.getAPI().init();

        // Scheduler-dependent startup work must happen here, not in onLoad() — Bukkit rejects
        // task scheduling while the plugin is still disabled.
        violationManager.startDecayTask();

        MovementChecks movementChecks = new MovementChecks(violationManager, moduleTuning, boostedMovement, movementTracer);
        suspectGui = new SuspectGui(this, violationManager);

        XoxoCommand xoxoCommand = new XoxoCommand(this, violationManager, moduleTuning, messages, suspectGui);
        Objects.requireNonNull(getCommand("xoxo")).setExecutor(xoxoCommand);
        Objects.requireNonNull(getCommand("xoxo")).setTabCompleter(xoxoCommand);

        getServer().getPluginManager().registerEvents(
                new PlayerDataListener(packetClickListener), this
        );
        getServer().getPluginManager().registerEvents(movementChecks, this);
        getServer().getPluginManager().registerEvents(combatChecks, this);
        getServer().getPluginManager().registerEvents(suspectGui, this);

        int totalChecks = movementChecks.checkCount() + combatChecks.checkCount();

        getLogger().info("xoxo-AntiCheat modules successfully hooked! Checks loaded: " + totalChecks
                + " (movement: " + movementChecks.checkCount()
                + ", combat: " + combatChecks.checkCount() + ")");
    }

    @Override
    public void onDisable() {
        PacketEvents.getAPI().terminate();
        getLogger().info("xoxo-AntiCheat Plugin has been disabled!");
    }
}
