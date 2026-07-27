package com.rootrecord.minecraft.rootmcofficial.config;

import com.rootrecord.minecraft.common.config.RootMcDatabaseConfig;
import org.bukkit.configuration.file.FileConfiguration;

public final class OfficialConfig {

    public record PeerDb(
            String host,
            int port,
            String database,
            String username,
            String password,
            String tablePrefix,
            String jdbcParams,
            String mcmmoTablePrefix) {

        public boolean configured() {
            return host != null && !host.isBlank()
                    && database != null && !database.isBlank()
                    && username != null && !username.isBlank();
        }

        public RootMcDatabaseConfig.DatabaseSettings asSettings() {
            return new RootMcDatabaseConfig.DatabaseSettings(
                    true,
                    host,
                    port,
                    database,
                    username,
                    password,
                    tablePrefix == null || tablePrefix.isBlank() ? "root_" : tablePrefix,
                    jdbcParams == null || jdbcParams.isBlank()
                            ? RootMcDatabaseConfig.DEFAULT_JDBC_PARAMS
                            : jdbcParams,
                    3);
        }
    }

    public record Datasets(
            boolean playtime,
            boolean votes,
            boolean rewardClaims,
            boolean mcmmo,
            boolean permsNetwork,
            boolean activity,
            boolean cmdtest,
            boolean linkCache) {}

    private final boolean enabled;
    private final String role;
    private final PeerDb peer;
    private final int intervalSeconds;
    private final boolean onJoin;
    private final Datasets datasets;
    private final RootMcDatabaseConfig.DatabaseSettings local;
    /** Suffix only — prefixed with local/peer table-prefix (e.g. rootmc_playtime → root_rootmc_playtime). */
    private final String playtimeTableName;
    private final String playtimeMonthlyTableName;

    public OfficialConfig(
            boolean enabled,
            String role,
            PeerDb peer,
            int intervalSeconds,
            boolean onJoin,
            Datasets datasets,
            RootMcDatabaseConfig.DatabaseSettings local,
            String playtimeTableName,
            String playtimeMonthlyTableName) {
        this.enabled = enabled;
        this.role = role;
        this.peer = peer;
        this.intervalSeconds = intervalSeconds;
        this.onJoin = onJoin;
        this.datasets = datasets;
        this.local = local;
        this.playtimeTableName =
                playtimeTableName == null || playtimeTableName.isBlank() ? "rootmc_playtime" : playtimeTableName.trim();
        this.playtimeMonthlyTableName =
                playtimeMonthlyTableName == null || playtimeMonthlyTableName.isBlank()
                        ? "rootmc_playtime_monthly"
                        : playtimeMonthlyTableName.trim();
    }

    public static OfficialConfig from(org.bukkit.plugin.java.JavaPlugin plugin, FileConfiguration cfg) {
        PeerDb peer = new PeerDb(
                cfg.getString("peer.host", ""),
                Math.max(1, cfg.getInt("peer.port", 3306)),
                cfg.getString("peer.database", ""),
                cfg.getString("peer.username", ""),
                cfg.getString("peer.password", ""),
                cfg.getString("peer.table-prefix", "root_"),
                cfg.getString("peer.jdbc-params", RootMcDatabaseConfig.DEFAULT_JDBC_PARAMS),
                cfg.getString("peer.mcmmo-table-prefix", "mcmmo_"));
        Datasets datasets = new Datasets(
                cfg.getBoolean("sync.datasets.playtime", true),
                cfg.getBoolean("sync.datasets.votes", true),
                // Default true with votes; Claims sets false so playtime milestones catch up locally.
                cfg.getBoolean(
                        "sync.datasets.reward-claims",
                        cfg.getBoolean("sync.datasets.votes", true)),
                cfg.getBoolean("sync.datasets.mcmmo", true),
                cfg.getBoolean("sync.datasets.perms-network", true),
                cfg.getBoolean("sync.datasets.activity", true),
                cfg.getBoolean("sync.datasets.cmdtest", true),
                cfg.getBoolean("sync.datasets.link-cache", true));
        return new OfficialConfig(
                cfg.getBoolean("enabled", false),
                cfg.getString("role", "towny"),
                peer,
                Math.max(15, cfg.getInt("sync.interval-seconds", 60)),
                cfg.getBoolean("sync.on-join", true),
                datasets,
                RootMcDatabaseConfig.resolve(plugin, null),
                cfg.getString("tables.playtime", "rootmc_playtime"),
                cfg.getString("tables.playtime-monthly", "rootmc_playtime_monthly"));
    }

    public boolean enabled() {
        return enabled;
    }

    public String role() {
        return role == null ? "towny" : role;
    }

    public PeerDb peer() {
        return peer;
    }

    public int intervalSeconds() {
        return intervalSeconds;
    }

    public boolean onJoin() {
        return onJoin;
    }

    public Datasets datasets() {
        return datasets;
    }

    public RootMcDatabaseConfig.DatabaseSettings local() {
        return local;
    }

    public boolean ready() {
        return enabled
                && peer != null
                && peer.configured()
                && local != null
                && local.isConfigured();
    }

    public String localPrefix() {
        return local == null || local.tablePrefix() == null || local.tablePrefix().isBlank()
                ? "root_"
                : local.tablePrefix();
    }

    public String peerPrefix() {
        return peer.tablePrefix() == null || peer.tablePrefix().isBlank() ? "root_" : peer.tablePrefix();
    }

    public String localPlaytimeTable() {
        return localPrefix() + playtimeTableName;
    }

    public String peerPlaytimeTable() {
        return peerPrefix() + playtimeTableName;
    }

    public String localPlaytimeMonthlyTable() {
        return localPrefix() + playtimeMonthlyTableName;
    }

    public String peerPlaytimeMonthlyTable() {
        return peerPrefix() + playtimeMonthlyTableName;
    }
}
