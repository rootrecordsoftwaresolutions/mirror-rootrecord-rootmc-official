package com.rootrecord.minecraft.rootmcofficial.command;

import com.rootrecord.minecraft.rootmcofficial.RootMcOfficialPlugin;
import com.rootrecord.minecraft.rootmcofficial.sync.ProgressionSyncService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OfficialCommand implements CommandExecutor, TabCompleter {

    private final RootMcOfficialPlugin plugin;

    public OfficialCommand(RootMcOfficialPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("rootmcofficial.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        ProgressionSyncService sync = plugin.sync();
        switch (sub) {
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "--- RootMC-Official ---");
                sender.sendMessage(ChatColor.GRAY + "Role: " + ChatColor.WHITE + plugin.config().role());
                sender.sendMessage(ChatColor.GRAY + "Enabled: " + ChatColor.WHITE + plugin.config().enabled());
                sender.sendMessage(ChatColor.GRAY + "Ready: " + ChatColor.WHITE + plugin.config().ready());
                sender.sendMessage(ChatColor.GRAY + "Peer reachable: "
                        + ChatColor.WHITE + (sync != null && sync.peerReachable()));
                if (sync != null) {
                    long ms = sync.lastSyncMs();
                    sender.sendMessage(ChatColor.GRAY + "Last sync: " + ChatColor.WHITE
                            + (ms <= 0 ? "(never)" : Instant.ofEpochMilli(ms).toString()));
                    sender.sendMessage(ChatColor.GRAY + "Last result: " + ChatColor.WHITE + sync.lastResult());
                    if (!sync.lastError().isBlank()) {
                        sender.sendMessage(ChatColor.RED + "Last error: " + sync.lastError());
                    }
                }
            }
            case "sync" -> {
                if (sync == null || !plugin.config().ready()) {
                    sender.sendMessage(ChatColor.RED + "Sync not ready — set enabled + peer MySQL in rootmc-official.yml.");
                    return true;
                }
                sender.sendMessage(ChatColor.YELLOW + "Running official peer sync…");
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    var stats = sync.syncNow(null);
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            sender.sendMessage(ChatColor.GREEN + "Sync done — rows≈" + stats.rowsTouched()
                                    + (stats.errors() > 0 ? ChatColor.RED + " errors=" + stats.errors() : "")));
                });
            }
            case "reload" -> {
                plugin.reloadAll();
                sender.sendMessage(ChatColor.GREEN + "RootMC-Official reloaded.");
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "Usage: /rootmcofficial <status|sync|reload>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("rootmcofficial.admin") || args.length != 1) {
            return List.of();
        }
        String p = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : List.of("status", "sync", "reload")) {
            if (o.startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }
}
