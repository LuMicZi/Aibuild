package com.aibuild.command;

import com.aibuild.Aibuild;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class AibuildCommand implements CommandExecutor {

    private final Aibuild plugin;

    public AibuildCommand(Aibuild plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list":
                handleList(sender);
                break;
            case "build":
                handleBuild(sender, args);
                break;
            case "gui":
                handleGui(sender);
                break;
            case "undo":
                handleUndo(sender);
                break;
            case "tool":
                handleTool(sender);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "language":
            case "lang":
                handleLanguage(sender, args);
                break;
            case "help":
            case "?":
                sendHelp(sender);
                break;
            default:
                sender.sendMessage(plugin.msg("cmd-unknown"));
                sendHelp(sender);
                break;
        }
        return true;
    }

    private void handleList(CommandSender sender) {
        List<String> names = plugin.getSchematicManager().getSchematicNames();
        if (names.isEmpty()) {
            sender.sendMessage(plugin.msg("schematic-list-empty"));
            return;
        }
        Collections.sort(names);
        sender.sendMessage(plugin.msg("schematic-list"));
        for (int i = 0; i < names.size(); i++) {
            sender.sendMessage("  " + (i + 1) + ". " + names.get(i));
        }
        sender.sendMessage("");
        sender.sendMessage(plugin.msgRaw("schematic-list-footer"));
    }

    private void handleBuild(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.msg("player-only"));
            return;
        }
        if (!sender.hasPermission("aibuild.build")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.msg("cmd-usage-build"));
            return;
        }
        plugin.getSchematicManager().pasteSchematic((Player) sender, args[1]);
    }

    private void handleGui(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.msg("player-only"));
            return;
        }
        if (!sender.hasPermission("aibuild.gui")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        plugin.getSchematicGui().openGui((Player) sender);
    }

    private void handleUndo(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.msg("player-only"));
            return;
        }
        if (!sender.hasPermission("aibuild.undo")) {
            sender.sendMessage(plugin.msg("undo-no-perm"));
            return;
        }
        plugin.getSchematicManager().undoSchematicAtPlayer((Player) sender);
    }

    private void handleTool(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.msg("player-only"));
            return;
        }
        if (!sender.hasPermission("aibuild.tool")) {
            sender.sendMessage(plugin.msg("tool-no-perm"));
            return;
        }
        plugin.getSelectionManager().giveTool((Player) sender);
        sender.sendMessage(plugin.msg("tool-received"));
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("aibuild.reload")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        plugin.reloadPlugin();
        sender.sendMessage(plugin.msg("reload-success"));
        int count = plugin.getSchematicManager().getSchematicNames().size();
        sender.sendMessage(plugin.getPrefix() + "Loaded " + count + " template(s)");
    }

    private void handleLanguage(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aibuild.reload")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.msg("lang-current"));
            sender.sendMessage(plugin.getPrefix() + plugin.msgRaw("lang-help"));
            sender.sendMessage(plugin.getPrefix() + plugin.msgRaw("lang-usage"));
            return;
        }
        boolean ok = plugin.setLanguage(args[1]);
        if (ok) {
            sender.sendMessage(plugin.msg("lang-set-success"));
        } else {
            sender.sendMessage(plugin.msg("lang-unknown"));
            sender.sendMessage(plugin.getPrefix() + plugin.msgRaw("lang-usage"));
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage(plugin.msgRaw("help-title"));
        sender.sendMessage(plugin.msgRaw("help-author"));
        sender.sendMessage("");
        sender.sendMessage(plugin.msgRaw("help-list"));
        sender.sendMessage(plugin.msgRaw("help-build"));
        sender.sendMessage(plugin.msgRaw("help-gui"));
        sender.sendMessage(plugin.msgRaw("help-tool"));
        sender.sendMessage(plugin.msgRaw("help-undo"));
        sender.sendMessage(plugin.msgRaw("help-reload"));
        sender.sendMessage(plugin.msgRaw("help-language"));
        sender.sendMessage(plugin.msgRaw("help-help"));
        sender.sendMessage("");
        sender.sendMessage(plugin.msgRaw("help-alias"));
        sender.sendMessage("");
    }
}
