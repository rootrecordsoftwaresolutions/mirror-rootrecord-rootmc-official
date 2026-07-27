package com.rootrecord.minecraft.rootmcofficial.listener;

import com.rootrecord.minecraft.rootmcofficial.RootMcOfficialPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class OfficialJoinListener implements Listener {

    private final RootMcOfficialPlugin plugin;

    public OfficialJoinListener(RootMcOfficialPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.sync() == null || !plugin.config().ready() || !plugin.config().onJoin()) {
            return;
        }
        var uuid = event.getPlayer().getUniqueId();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> plugin.sync().syncNow(uuid));
    }
}
