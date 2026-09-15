package com.xoxoac.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Now only handles per-player cleanup on quit. The packet-level move/swing/attack tracking
 * pipeline that used to live here (PlayerData/PlayerDataManager/MoveSnapshot/CheckManager) only
 * ever existed to feed TimerA, which has been removed — see MovementChecks and CombatChecks for
 * the actual movement/combat checks, which track their own per-player state directly.
 */
public final class PlayerDataListener implements Listener {

    private final PacketClickListener packetClickListener;

    public PlayerDataListener(PacketClickListener packetClickListener) {
        this.packetClickListener = packetClickListener;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        packetClickListener.handleQuit(event.getPlayer().getUniqueId());
    }
}
