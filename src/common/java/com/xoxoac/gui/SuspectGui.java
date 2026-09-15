package com.xoxoac.gui;

import com.xoxoac.modules.SuspicionTracker;
import com.xoxoac.modules.ViolationManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Double-chest GUI listing the most-flagged players as their heads, worst offenders first, with
 * a page-forward arrow when there are more suspects than fit in one page. Read-only — clicking
 * never lets an item be taken out or placed in; a left-click on a head instead:
 *   - if the target is online: teleports the clicking staff member to them, switches the staff
 *     member to Gamemode 3 (spectator) and grants them a permanent, particle-free invisibility
 *     so they can observe the suspect unnoticed.
 *
 * Invisibility here is deliberately NOT vanish-from-tab-list/plugin-vanish — just a plain
 * INVISIBILITY potion effect with no particles/icon, which is enough to not be seen by the
 * suspect while spectating. It's removed either by drinking milk (which clears potion effects
 * vanilla-side anyway) or via /xoxo unvanish.
 */
public final class SuspectGui implements Listener {

    private static final int ROWS = 6; // double chest
    private static final int PAGE_SIZE = ROWS * 9 - 9; // reserve bottom row for nav
    private static final int NEXT_PAGE_SLOT = ROWS * 9 - 1;
    private static final int PREV_PAGE_SLOT = ROWS * 9 - 9;

    private final Plugin plugin;
    private final ViolationManager violationManager;
    private final Set<UUID> acVanished = ConcurrentHashMap.newKeySet();

    // Tracks which UUID each open GUI session slot maps to, and which page each viewer is on —
    // keyed by the viewing staff member's UUID (a fresh Inventory is built per open/page-turn).
    private final java.util.Map<UUID, List<SuspicionTracker.SuspicionEntry>> sessionEntries = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, Integer> sessionPage = new ConcurrentHashMap<>();
    // Identifies "is this inventory one of ours" by object identity rather than by title string —
    // comparing InventoryView titles is fragile across Paper API versions (Adventure Component
    // migration deprecated the plain-String getTitle() accessor in some builds), so instead we
    // just remember which Inventory objects we personally created.
    private final Set<Inventory> ownInventories = Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    public SuspectGui(Plugin plugin, ViolationManager violationManager) {
        this.plugin = plugin;
        this.violationManager = violationManager;
    }

    public void open(Player viewer) {
        List<SuspicionTracker.SuspicionEntry> entries = violationManager.suspicionTracker().topSuspects(200);
        sessionEntries.put(viewer.getUniqueId(), entries);
        sessionPage.put(viewer.getUniqueId(), 0);
        viewer.openInventory(trackAndBuild(entries, 0));
    }

    /** Builds a page and registers the resulting Inventory as ours, so onClick can recognize it. */
    private Inventory trackAndBuild(List<SuspicionTracker.SuspicionEntry> entries, int page) {
        Inventory inv = buildPage(entries, page);
        ownInventories.add(inv);
        return inv;
    }

    private Inventory buildPage(List<SuspicionTracker.SuspicionEntry> entries, int page) {
        Inventory inv = Bukkit.createInventory(null, ROWS * 9, "Нарушители");

        int from = page * PAGE_SIZE;
        int to = Math.min(entries.size(), from + PAGE_SIZE);

        int slot = 0;
        for (int i = from; i < to; i++) {
            inv.setItem(slot++, headFor(entries.get(i)));
        }

        if (page > 0) {
            inv.setItem(PREV_PAGE_SLOT, navItem(Material.ARROW, "&eНазад"));
        }
        if (to < entries.size()) {
            inv.setItem(NEXT_PAGE_SLOT, navItem(Material.ARROW, "&eДалее"));
        }

        return inv;
    }

    private ItemStack headFor(SuspicionTracker.SuspicionEntry entry) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        if (meta != null) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(entry.uuid());
            meta.setOwningPlayer(offline);
            meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    "&f" + entry.playerName()));

            List<String> lore = new ArrayList<>();
            lore.add(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    "&7Подозрение: " + suspicionColor(entry.suspicionPercent())
                            + String.format(Locale.US, "%.1f", entry.suspicionPercent()) + "%"));
            if (!entry.topModules().isEmpty()) {
                lore.add(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                        "&7Модули: &f" + String.join(", ", entry.topModules())));
            }
            boolean online = Bukkit.getPlayer(entry.uuid()) != null;
            lore.add(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    online ? "&aВ сети &7— ЛКМ, чтобы телепортироваться" : "&8Не в сети"));
            meta.setLore(lore);
            skull.setItemMeta(meta);
        }
        return skull;
    }

    /**
     * Colour ramp for the suspicion percentage: green at 0%, sliding to yellow around 60-70%,
     * red past 80%, and dark-red ("blood") past ~99% for an almost-certain read on the player.
     * Matches the server owner's exact spec — this is a discrete step ramp (not an interpolated
     * gradient), since chat/lore text only supports named colour codes, not arbitrary RGB blends.
     */
    private String suspicionColor(double percent) {
        if (percent >= 99.0) return "&4";  // dark red / "blood" — near-certain
        if (percent >= 80.0) return "&c";  // red
        if (percent >= 60.0) return "&e";  // yellow
        if (percent >= 30.0) return "&6";  // orange — soft midpoint between green and yellow
        return "&a";                       // green — low suspicion
    }

    private ItemStack navItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&', name));
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        if (!ownInventories.contains(event.getInventory())) return;

        // Read-only: never allow taking/placing/shift-clicking items out of this GUI.
        event.setCancelled(true);

        List<SuspicionTracker.SuspicionEntry> entries = sessionEntries.get(viewer.getUniqueId());
        if (entries == null) return;
        int page = sessionPage.getOrDefault(viewer.getUniqueId(), 0);

        int slot = event.getRawSlot();
        if (slot == NEXT_PAGE_SLOT && (page + 1) * PAGE_SIZE < entries.size()) {
            Inventory old = event.getInventory();
            sessionPage.put(viewer.getUniqueId(), page + 1);
            viewer.openInventory(trackAndBuild(entries, page + 1));
            ownInventories.remove(old);
            return;
        }
        if (slot == PREV_PAGE_SLOT && page > 0) {
            Inventory old = event.getInventory();
            sessionPage.put(viewer.getUniqueId(), page - 1);
            viewer.openInventory(trackAndBuild(entries, page - 1));
            ownInventories.remove(old);
            return;
        }

        if (slot < 0 || slot >= PAGE_SIZE) return;
        int index = page * PAGE_SIZE + slot;
        if (index < 0 || index >= entries.size()) return;

        SuspicionTracker.SuspicionEntry entry = entries.get(index);
        Player target = Bukkit.getPlayer(entry.uuid());
        if (target == null) return;

        teleportForInspection(viewer, target);
    }

    /** Teleports the staff member to the suspect, sets GM3 + permanent particle-free invisibility. */
    private void teleportForInspection(Player staff, Player target) {
        staff.closeInventory();
        staff.teleport(target.getLocation());
        staff.setGameMode(GameMode.SPECTATOR);

        staff.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false, false));
        acVanished.add(staff.getUniqueId());
    }

    /** Removes the AC-granted invisibility (called by /xoxo unvanish or on drinking milk). */
    public boolean clearVanish(Player player) {
        if (!acVanished.remove(player.getUniqueId())) return false;
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        return true;
    }

    public boolean isAcVanished(Player player) {
        return acVanished.contains(player.getUniqueId());
    }

    @EventHandler
    public void onMilk(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() != Material.MILK_BUCKET) return;
        clearVanish(event.getPlayer());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        // Just drop the Inventory object from the "is this ours" set — page-turns close-then-
        // reopen a new Inventory synchronously in the same click, so per-player session state
        // (sessionEntries/sessionPage) is intentionally NOT cleared here; it's small (a couple of
        // map entries per online staff member) and is cleaned up on quit instead, once we know
        // for certain the player isn't about to reopen a different page of the same GUI.
        ownInventories.remove(event.getInventory());
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        sessionEntries.remove(event.getPlayer().getUniqueId());
        sessionPage.remove(event.getPlayer().getUniqueId());
        // Deliberately does NOT clear acVanished here — a staff member who disconnects mid-
        // inspection should not silently lose the vanish state; it should still require milk or
        // /xoxo unvanish once they're back, same as if they'd just alt-tabbed.
    }
}
