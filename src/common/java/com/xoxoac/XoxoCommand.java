package com.xoxoac;

import com.xoxoac.config.Messages;
import com.xoxoac.config.ModuleTuning;
import com.xoxoac.gui.SuspectGui;
import com.xoxoac.modules.ViolationManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Deliberately small: only the three commands the server owner actually wants are here.
 *   /xoxo check   — opens the suspects GUI (see SuspectGui) instead of a text list in chat.
 *   /xoxo reload  — reloads config.yml. Operator only (isOp()), no separate admin permission.
 *   /xoxo alert   — toggles a staff member's own live flag-alert feed. A player toggles their
 *                   own chat feed; run from console it toggles whether flags are written to the
 *                   server console log at all. This replaces the old, broken "alerts" command —
 *                   the previous implementation conflated "is a player" with "should go to chat",
 *                   which is exactly why it never routed correctly.
 *   /xoxo unvanish — clears the AC-granted spectate invisibility from the suspects GUI (the same
 *                   effect a milk bucket clears). Helper or operator only.
 *
 * Permissions are LuckPerms-managed nodes, not an in-plugin list:
 *   xoxoac.bypass — full anticheat bypass for the holder (checked directly in ViolationManager).
 *   xoxoac.helper — access to /xoxo check and /xoxo alert.
 * /xoxo reload remains operator-only regardless of these nodes.
 */
public class XoxoCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_HELPER = "xoxoac.helper";

    private final Plugin plugin;
    private final ViolationManager violationManager;
    private final ModuleTuning moduleTuning;
    private final Messages messages;
    private final SuspectGui suspectGui;

    public XoxoCommand(Plugin plugin, ViolationManager violationManager,
                        ModuleTuning moduleTuning, Messages messages, SuspectGui suspectGui) {
        this.plugin = plugin;
        this.violationManager = violationManager;
        this.moduleTuning = moduleTuning;
        this.messages = messages;
        this.suspectGui = suspectGui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "check"    -> handleCheck(sender);
            case "reload"   -> handleReload(sender);
            case "alert"    -> handleAlert(sender, args);
            case "unvanish" -> handleUnvanish(sender);
            default         -> sendHelp(sender);
        }

        return true;
    }

    private boolean requireHelperOrOp(CommandSender sender) {
        if (sender.hasPermission(PERM_HELPER) || sender.isOp()) return true;
        sender.sendMessage(messages.prefixed("no-permission"));
        return false;
    }

    // ── /xoxo check ───────────────────────────────────────────────────────────

    private void handleCheck(CommandSender sender) {
        if (!requireHelperOrOp(sender)) return;
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.prefixed("alert-console"));
            return;
        }
        suspectGui.open(player);
    }

    // ── /xoxo reload — operator only ────────────────────────────────────────

    private void handleReload(CommandSender sender) {
        if (!sender.isOp() && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            sender.sendMessage(messages.prefixed("no-permission"));
            return;
        }
        plugin.reloadConfig();
        moduleTuning.reload();
        messages.reload();
        sender.sendMessage(messages.prefixed("reload-success"));
    }

    // ── /xoxo alert [console] ────────────────────────────────────────────────

    private void handleAlert(CommandSender sender, String[] args) {
        if (!requireHelperOrOp(sender)) return;

        // Run from the console: toggles whether flags get written to the server console log at
        // all (independent of any individual staff member's own chat feed below).
        if (!(sender instanceof Player)) {
            boolean nowEnabled = violationManager.toggleConsoleLogging();
            sender.sendMessage(nowEnabled
                    ? messages.prefixed("alert-console-enabled")
                    : messages.prefixed("alert-console-disabled"));
            return;
        }

        Player player = (Player) sender;
        boolean nowMuted = violationManager.toggleAlertMute(player.getUniqueId());
        player.sendMessage(nowMuted
                ? messages.prefixed("alert-muted")
                : messages.prefixed("alert-enabled"));
    }

    // ── /xoxo unvanish ────────────────────────────────────────────────────────

    private void handleUnvanish(CommandSender sender) {
        if (!requireHelperOrOp(sender)) return;
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.prefixed("alert-console"));
            return;
        }
        boolean cleared = suspectGui.clearVanish(player);
        player.sendMessage(cleared
                ? messages.prefixed("unvanish-cleared")
                : messages.prefixed("unvanish-not-active"));
    }

    // ── Help ──────────────────────────────────────────────────────────────────

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(messages.prefixed("help-header"));
        if (sender.hasPermission(PERM_HELPER) || sender.isOp()) {
            sender.sendMessage("  §f/xoxo check       §7— Открыть GUI со списком нарушителей");
            sender.sendMessage("  §f/xoxo alert       §7— Переключить свою ленту оповещений о флагах");
            sender.sendMessage("  §f/xoxo unvanish    §7— Снять невидимость, выданную GUI проверки");
        }
        if (sender.isOp()) {
            sender.sendMessage("  §f/xoxo reload      §7— Перезагрузить конфигурацию");
        }
    }

    // ── Tab Completion ────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> suggestions = new ArrayList<>();
        boolean helper = sender.hasPermission(PERM_HELPER) || sender.isOp();

        if (args.length == 1) {
            if (helper) suggestions.addAll(List.of("check", "alert", "unvanish"));
            if (sender.isOp()) suggestions.add("reload");
        } else if (args.length == 2 && "alert".equalsIgnoreCase(args[0]) && sender.isOp()) {
            suggestions.add("console");
        }

        String typed = args[args.length - 1].toLowerCase();
        suggestions.removeIf(s -> !s.toLowerCase().startsWith(typed));
        return suggestions;
    }
}
