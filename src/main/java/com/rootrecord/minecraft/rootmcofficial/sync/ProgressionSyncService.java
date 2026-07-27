package com.rootrecord.minecraft.rootmcofficial.sync;

import com.rootrecord.minecraft.common.mysql.MysqlConnections;
import com.rootrecord.minecraft.rootmcofficial.config.OfficialConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/** Pulls progression rows from peer MySQL and merges into local (MAX / insert-missing). */
public final class ProgressionSyncService {

    private static final String[] MCMMO_SKILLS = {
        "mining", "woodcutting", "repair", "unarmed", "herbalism", "excavation",
        "archery", "swords", "axes", "acrobatics", "taming", "fishing", "alchemy",
        "crossbows", "tridents", "maces", "spears"
    };

    private final JavaPlugin plugin;
    private volatile OfficialConfig config;
    private final AtomicReference<String> lastResult = new AtomicReference<>("never");
    private final AtomicReference<String> lastError = new AtomicReference<>("");
    private final AtomicLong lastSyncMs = new AtomicLong(0);
    private final AtomicLong lastRows = new AtomicLong(0);

    public ProgressionSyncService(JavaPlugin plugin, OfficialConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void reload(OfficialConfig config) {
        this.config = config;
    }

    public OfficialConfig config() {
        return config;
    }

    public String lastResult() {
        return lastResult.get();
    }

    public String lastError() {
        return lastError.get();
    }

    public long lastSyncMs() {
        return lastSyncMs.get();
    }

    public long lastRows() {
        return lastRows.get();
    }

    public boolean peerReachable() {
        if (config == null || !config.peer().configured()) {
            return false;
        }
        try (Connection ignored = MysqlConnections.open(config.peer().asSettings())) {
            return true;
        } catch (SQLException ex) {
            return false;
        }
    }

    /** Full sync of all enabled datasets (optional single-player filter). */
    public SyncStats syncNow(UUID onlyPlayer) {
        OfficialConfig cfg = config;
        if (cfg == null || !cfg.ready()) {
            lastError.set("not ready (enabled + peer + local database.yml)");
            lastResult.set("skipped");
            return new SyncStats(0, 0, lastError.get());
        }
        int touched = 0;
        int errors = 0;
        StringBuilder err = new StringBuilder();
        try (Connection local = MysqlConnections.open(cfg.local());
                Connection peer = MysqlConnections.open(cfg.peer().asSettings())) {
            OfficialConfig.Datasets d = cfg.datasets();
            if (d.playtime()) {
                try {
                    touched += syncPlaytime(local, peer, cfg, onlyPlayer);
                } catch (SQLException ex) {
                    errors++;
                    appendErr(err, "playtime", ex);
                    plugin.getLogger().log(Level.WARNING, "Official playtime sync failed: " + ex.getMessage(), ex);
                }
            }
            if (d.votes()) {
                try {
                    touched += syncVotes(local, peer, cfg, onlyPlayer);
                } catch (SQLException ex) {
                    errors++;
                    appendErr(err, "votes", ex);
                    plugin.getLogger().log(Level.WARNING, "Official votes sync failed: " + ex.getMessage(), ex);
                }
            }
            if (d.mcmmo()) {
                try {
                    touched += syncMcMmo(local, peer, cfg, onlyPlayer);
                } catch (SQLException ex) {
                    errors++;
                    appendErr(err, "mcmmo", ex);
                    plugin.getLogger().log(Level.WARNING, "Official mcmmo sync failed: " + ex.getMessage(), ex);
                }
            }
            if (d.permsNetwork()) {
                try {
                    touched += syncPermsNetwork(local, peer, cfg, onlyPlayer);
                } catch (SQLException ex) {
                    errors++;
                    appendErr(err, "perms", ex);
                    plugin.getLogger().log(Level.WARNING, "Official perms sync failed: " + ex.getMessage(), ex);
                }
            }
            if (d.activity()) {
                try {
                    touched += syncActivity(local, peer, cfg, onlyPlayer);
                } catch (SQLException ex) {
                    errors++;
                    appendErr(err, "activity", ex);
                    plugin.getLogger().log(Level.WARNING, "Official activity sync failed: " + ex.getMessage(), ex);
                }
            }
            if (d.cmdtest()) {
                try {
                    touched += syncCmdtest(local, peer, cfg, onlyPlayer);
                } catch (SQLException ex) {
                    errors++;
                    appendErr(err, "cmdtest", ex);
                    plugin.getLogger().log(Level.WARNING, "Official cmdtest sync failed: " + ex.getMessage(), ex);
                }
            }
            if (d.linkCache()) {
                try {
                    touched += syncLinkCache(local, peer, cfg, onlyPlayer);
                } catch (SQLException ex) {
                    errors++;
                    appendErr(err, "link-cache", ex);
                    plugin.getLogger().log(Level.WARNING, "Official link-cache sync failed: " + ex.getMessage(), ex);
                }
            }
        } catch (SQLException ex) {
            errors++;
            appendErr(err, "connect", ex);
            plugin.getLogger().log(Level.WARNING, "Official sync failed: " + ex.getMessage(), ex);
        }
        lastSyncMs.set(System.currentTimeMillis());
        lastRows.set(touched);
        if (errors > 0) {
            lastError.set(err.toString());
            lastResult.set("error rows=" + touched);
        } else {
            lastError.set("");
            lastResult.set("ok rows=" + touched
                    + (onlyPlayer != null ? " uuid=" + onlyPlayer : " full"));
        }
        return new SyncStats(touched, errors, lastError.get());
    }

    private static void appendErr(StringBuilder err, String label, SQLException ex) {
        if (err.length() > 0) {
            err.append("; ");
        }
        err.append(label).append(": ").append(ex.getMessage());
    }

    private int syncPlaytime(Connection local, Connection peer, OfficialConfig cfg, UUID only)
            throws SQLException {
        String lp = cfg.localPlaytimeTable();
        String pp = cfg.peerPlaytimeTable();
        String lm = cfg.localPlaytimeMonthlyTable();
        String pm = cfg.peerPlaytimeMonthlyTable();
        int n = 0;
        if (!tableExists(peer, pp) || !tableExists(local, lp)) {
            return 0;
        }
        if (columnExists(peer, pp, "scope")) {
            n += syncPlaytimeScoped(local, peer, lp, pp, only);
        } else {
            // Towny (or older Claims) legacy: one row per player, total_playtime_seconds.
            n += syncPlaytimeLegacy(local, peer, lp, pp, peerRoleScope(cfg), only);
        }
        n += recomputeStarPlaytime(local, lp, only);
        if (tableExists(peer, pm) && tableExists(local, lm)) {
            if (columnExists(peer, pm, "scope")) {
                n += syncPlaytimeMonthlyScoped(local, peer, lm, pm, only);
            } else if (columnExists(peer, pm, "playtime_seconds") || columnExists(peer, pm, "seconds")) {
                n += syncPlaytimeMonthlyLegacy(local, peer, lm, pm, peerRoleScope(cfg), only);
            }
            n += recomputeStarPlaytimeMonthly(local, lm, only);
        }
        return n;
    }

    /** Peer host label used as scope when peer table is legacy (no scope column). Past data → towny. */
    private static String peerRoleScope(OfficialConfig cfg) {
        String role = cfg.role() == null ? "" : cfg.role().trim().toLowerCase(Locale.ROOT);
        // Claims pulling Towny legacy → towny. Towny pulling Claims legacy → claims.
        if ("claims".equals(role) || "c".equals(role) || "g2".equals(role)) {
            return "towny";
        }
        return "claims";
    }

    private static String normalizeServerScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "peer";
        }
        String s = scope.trim().toLowerCase(Locale.ROOT);
        if ("*".equals(s)) {
            return "*";
        }
        return switch (s) {
            case "claims", "c", "g2", "gen2", "gen-2" -> "claims";
            case "towny", "t", "g1", "gen1", "gen-1", "official" -> "towny";
            default -> scope.trim();
        };
    }

    private int syncPlaytimeScoped(
            Connection local, Connection peer, String lp, String pp, UUID only) throws SQLException {
        int n = 0;
        String sql = only == null
                ? "SELECT uuid, scope, username, seconds, first_join_at, last_login_at, updated_at FROM " + pp
                : "SELECT uuid, scope, username, seconds, first_join_at, last_login_at, updated_at FROM " + pp
                        + " WHERE uuid = ?";
        try (PreparedStatement ps = peer.prepareStatement(sql)) {
            if (only != null) {
                ps.setString(1, only.toString());
            }
            try (ResultSet rs = ps.executeQuery();
                    PreparedStatement up = upsertScopedPlaytime(local, lp)) {
                while (rs.next()) {
                    String scope = normalizeServerScope(rs.getString("scope"));
                    if (scope == null || scope.equals("*")) {
                        continue;
                    }
                    bindScopedPlaytime(
                            up,
                            rs.getString("uuid"),
                            scope,
                            rs.getString("username"),
                            rs.getLong("seconds"),
                            rs.getTimestamp("first_join_at"),
                            rs.getTimestamp("last_login_at"),
                            rs.getTimestamp("updated_at"));
                    up.addBatch();
                    n++;
                }
                up.executeBatch();
            }
        }
        return n;
    }

    private int syncPlaytimeLegacy(
            Connection local, Connection peer, String lp, String pp, String peerScope, UUID only)
            throws SQLException {
        int n = 0;
        String secondsCol = columnExists(peer, pp, "total_playtime_seconds")
                ? "total_playtime_seconds"
                : (columnExists(peer, pp, "seconds") ? "seconds" : "playtime_seconds");
        String sql = only == null
                ? "SELECT uuid, username, " + secondsCol
                        + " AS seconds, first_join_at, last_login_at, updated_at FROM " + pp
                : "SELECT uuid, username, " + secondsCol
                        + " AS seconds, first_join_at, last_login_at, updated_at FROM " + pp
                        + " WHERE uuid = ?";
        try (PreparedStatement ps = peer.prepareStatement(sql)) {
            if (only != null) {
                ps.setString(1, only.toString());
            }
            try (ResultSet rs = ps.executeQuery();
                    PreparedStatement up = upsertScopedPlaytime(local, lp)) {
                while (rs.next()) {
                    bindScopedPlaytime(
                            up,
                            rs.getString("uuid"),
                            peerScope,
                            rs.getString("username"),
                            rs.getLong("seconds"),
                            rs.getTimestamp("first_join_at"),
                            rs.getTimestamp("last_login_at"),
                            rs.getTimestamp("updated_at"));
                    up.addBatch();
                    n++;
                }
                up.executeBatch();
            }
        }
        return n;
    }

    private static PreparedStatement upsertScopedPlaytime(Connection local, String lp) throws SQLException {
        return local.prepareStatement(
                """
                INSERT INTO %s (uuid, scope, username, seconds, first_join_at, last_login_at, updated_at)
                VALUES (?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                  username = VALUES(username),
                  seconds = GREATEST(%s.seconds, VALUES(seconds)),
                  first_join_at = LEAST(%s.first_join_at, VALUES(first_join_at)),
                  last_login_at = GREATEST(%s.last_login_at, VALUES(last_login_at)),
                  updated_at = GREATEST(%s.updated_at, VALUES(updated_at))
                """
                        .formatted(lp, lp, lp, lp, lp));
    }

    private static void bindScopedPlaytime(
            PreparedStatement up,
            String uuid,
            String scope,
            String username,
            long seconds,
            Timestamp firstJoin,
            Timestamp lastLogin,
            Timestamp updated)
            throws SQLException {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        up.setString(1, uuid);
        up.setString(2, scope == null || scope.isBlank() ? "peer" : scope.trim());
        up.setString(3, username == null || username.isBlank() ? "Unknown" : username);
        up.setLong(4, Math.max(0L, seconds));
        up.setTimestamp(5, firstJoin != null ? firstJoin : now);
        up.setTimestamp(6, lastLogin != null ? lastLogin : now);
        up.setTimestamp(7, updated != null ? updated : now);
    }

    private int syncPlaytimeMonthlyScoped(
            Connection local, Connection peer, String lm, String pm, UUID only) throws SQLException {
        int n = 0;
        String msql = only == null
                ? "SELECT uuid, scope, month_key, seconds, updated_at FROM " + pm
                : "SELECT uuid, scope, month_key, seconds, updated_at FROM " + pm + " WHERE uuid = ?";
        try (PreparedStatement ps = peer.prepareStatement(msql)) {
            if (only != null) {
                ps.setString(1, only.toString());
            }
            try (ResultSet rs = ps.executeQuery();
                    PreparedStatement up = upsertScopedMonthly(local, lm)) {
                while (rs.next()) {
                    String scope = normalizeServerScope(rs.getString("scope"));
                    if (scope == null || scope.equals("*")) {
                        continue;
                    }
                    bindScopedMonthly(
                            up,
                            rs.getString("uuid"),
                            scope,
                            rs.getString("month_key"),
                            rs.getLong("seconds"),
                            rs.getTimestamp("updated_at"));
                    up.addBatch();
                    n++;
                }
                up.executeBatch();
            }
        }
        return n;
    }

    private int syncPlaytimeMonthlyLegacy(
            Connection local, Connection peer, String lm, String pm, String peerScope, UUID only)
            throws SQLException {
        int n = 0;
        String secondsCol = columnExists(peer, pm, "playtime_seconds") ? "playtime_seconds" : "seconds";
        String msql = only == null
                ? "SELECT uuid, month_key, " + secondsCol + " AS seconds, updated_at FROM " + pm
                : "SELECT uuid, month_key, " + secondsCol + " AS seconds, updated_at FROM " + pm
                        + " WHERE uuid = ?";
        try (PreparedStatement ps = peer.prepareStatement(msql)) {
            if (only != null) {
                ps.setString(1, only.toString());
            }
            try (ResultSet rs = ps.executeQuery();
                    PreparedStatement up = upsertScopedMonthly(local, lm)) {
                while (rs.next()) {
                    bindScopedMonthly(
                            up,
                            rs.getString("uuid"),
                            peerScope,
                            rs.getString("month_key"),
                            rs.getLong("seconds"),
                            rs.getTimestamp("updated_at"));
                    up.addBatch();
                    n++;
                }
                up.executeBatch();
            }
        }
        return n;
    }

    private static PreparedStatement upsertScopedMonthly(Connection local, String lm) throws SQLException {
        return local.prepareStatement(
                """
                INSERT INTO %s (uuid, scope, month_key, seconds, updated_at)
                VALUES (?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                  seconds = GREATEST(%s.seconds, VALUES(seconds)),
                  updated_at = GREATEST(%s.updated_at, VALUES(updated_at))
                """
                        .formatted(lm, lm, lm));
    }

    private static void bindScopedMonthly(
            PreparedStatement up,
            String uuid,
            String scope,
            String monthKey,
            long seconds,
            Timestamp updated)
            throws SQLException {
        up.setString(1, uuid);
        up.setString(2, scope == null || scope.isBlank() ? "peer" : scope.trim());
        up.setString(3, monthKey);
        up.setLong(4, Math.max(0L, seconds));
        up.setTimestamp(5, updated != null ? updated : new Timestamp(System.currentTimeMillis()));
    }

    /** scope=* must be network total = SUM(server scopes), not GREATEST of each host's local *. */
    private static int recomputeStarPlaytime(Connection local, String table, UUID only) throws SQLException {
        String sql =
                """
                INSERT INTO %s (uuid, scope, username, seconds, first_join_at, last_login_at, updated_at)
                SELECT uuid, '*',
                       COALESCE(MAX(username), 'unknown'),
                       COALESCE(SUM(seconds), 0),
                       MIN(first_join_at),
                       MAX(last_login_at),
                       UTC_TIMESTAMP()
                FROM %s
                WHERE scope <> '*'
                %s
                GROUP BY uuid
                ON DUPLICATE KEY UPDATE
                  username = VALUES(username),
                  seconds = VALUES(seconds),
                  first_join_at = LEAST(%s.first_join_at, VALUES(first_join_at)),
                  last_login_at = GREATEST(%s.last_login_at, VALUES(last_login_at)),
                  updated_at = UTC_TIMESTAMP()
                """
                        .formatted(
                                table,
                                table,
                                only != null ? "AND uuid = ?" : "",
                                table,
                                table);
        try (PreparedStatement ps = local.prepareStatement(sql)) {
            if (only != null) {
                ps.setString(1, only.toString());
            }
            return Math.max(0, ps.executeUpdate());
        }
    }

    private static int recomputeStarPlaytimeMonthly(Connection local, String table, UUID only)
            throws SQLException {
        String sql =
                """
                INSERT INTO %s (uuid, scope, month_key, seconds, updated_at)
                SELECT uuid, '*', month_key, COALESCE(SUM(seconds), 0), UTC_TIMESTAMP()
                FROM %s
                WHERE scope <> '*'
                %s
                GROUP BY uuid, month_key
                ON DUPLICATE KEY UPDATE
                  seconds = VALUES(seconds),
                  updated_at = UTC_TIMESTAMP()
                """
                        .formatted(table, table, only != null ? "AND uuid = ?" : "");
        try (PreparedStatement ps = local.prepareStatement(sql)) {
            if (only != null) {
                ps.setString(1, only.toString());
            }
            return Math.max(0, ps.executeUpdate());
        }
    }

    private int syncVotes(Connection local, Connection peer, OfficialConfig cfg, UUID only)
            throws SQLException {
        String lv = cfg.localPrefix() + "rewards_votes";
        String pv = cfg.peerPrefix() + "rewards_votes";
        String lc = cfg.localPrefix() + "rewards_claims";
        String pc = cfg.peerPrefix() + "rewards_claims";
        int n = 0;
        if (tableExists(peer, pv) && tableExists(local, lv)) {
            String sql = only == null
                    ? "SELECT uuid, service, voted_at, gold_earned FROM " + pv
                    : "SELECT uuid, service, voted_at, gold_earned FROM " + pv + " WHERE uuid = ?";
            try (PreparedStatement ps = peer.prepareStatement(sql)) {
                if (only != null) {
                    ps.setString(1, only.toString());
                }
                try (ResultSet rs = ps.executeQuery();
                        PreparedStatement up = local.prepareStatement(
                                """
                                INSERT INTO %s (uuid, service, voted_at, gold_earned)
                                SELECT ?,?,?,?
                                FROM DUAL
                                WHERE NOT EXISTS (
                                  SELECT 1 FROM %s x
                                  WHERE x.uuid = ? AND x.service = ? AND x.voted_at = ?
                                )
                                """
                                        .formatted(lv, lv))) {
                    while (rs.next()) {
                        String uuid = rs.getString("uuid");
                        String service = rs.getString("service");
                        Timestamp votedAt = rs.getTimestamp("voted_at");
                        up.setString(1, uuid);
                        up.setString(2, service);
                        up.setTimestamp(3, votedAt);
                        up.setDouble(4, rs.getDouble("gold_earned"));
                        up.setString(5, uuid);
                        up.setString(6, service);
                        up.setTimestamp(7, votedAt);
                        up.addBatch();
                        n++;
                    }
                    up.executeBatch();
                }
            }
        }
        if (cfg.datasets().rewardClaims() && tableExists(peer, pc) && tableExists(local, lc)) {
            String sql = only == null
                    ? "SELECT uuid, last_claimed_tier, updated_at FROM " + pc
                    : "SELECT uuid, last_claimed_tier, updated_at FROM " + pc + " WHERE uuid = ?";
            try (PreparedStatement ps = peer.prepareStatement(sql)) {
                if (only != null) {
                    ps.setString(1, only.toString());
                }
                try (ResultSet rs = ps.executeQuery();
                        PreparedStatement up = local.prepareStatement(
                                """
                                INSERT INTO %s (uuid, last_claimed_tier, updated_at)
                                VALUES (?,?,?)
                                ON DUPLICATE KEY UPDATE
                                  last_claimed_tier = GREATEST(%s.last_claimed_tier, VALUES(last_claimed_tier)),
                                  updated_at = GREATEST(%s.updated_at, VALUES(updated_at))
                                """
                                        .formatted(lc, lc, lc))) {
                    while (rs.next()) {
                        up.setString(1, rs.getString("uuid"));
                        up.setInt(2, rs.getInt("last_claimed_tier"));
                        up.setTimestamp(3, rs.getTimestamp("updated_at"));
                        up.addBatch();
                        n++;
                    }
                    up.executeBatch();
                }
            }
        }
        return n;
    }

    private int syncMcMmo(Connection local, Connection peer, OfficialConfig cfg, UUID only)
            throws SQLException {
        String pUsers = cfg.peer().mcmmoTablePrefix() + "users";
        String lUsers = resolveLocalMcmmoPrefix(cfg) + "users";
        String pSkills = cfg.peer().mcmmoTablePrefix() + "skills";
        String lSkills = resolveLocalMcmmoPrefix(cfg) + "skills";
        int n = 0;
        if (!tableExists(peer, pUsers) || !tableExists(local, lUsers)) {
            return 0;
        }
        if (only != null) {
            String name = lookupUsername(local, cfg.localPlaytimeTable(), only);
            if (name == null) {
                return 0;
            }
            try (PreparedStatement ps = peer.prepareStatement("SELECT id, user FROM " + pUsers + " WHERE user = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        n += upsertMcMmoUser(local, lUsers, rs.getInt("id"), rs.getString("user"));
                        int peerId = rs.getInt("id");
                        int localId = findMcMmoUserId(local, lUsers, name);
                        if (localId > 0 && tableExists(peer, pSkills) && tableExists(local, lSkills)) {
                            n += mergeMcMmoSkills(local, peer, lSkills, pSkills, localId, peerId);
                        }
                    }
                }
            }
            return n;
        }
        try (PreparedStatement ps = peer.prepareStatement("SELECT id, user FROM " + pUsers);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String user = rs.getString("user");
                int peerId = rs.getInt("id");
                n += upsertMcMmoUser(local, lUsers, peerId, user);
                int localId = findMcMmoUserId(local, lUsers, user);
                if (localId > 0 && tableExists(peer, pSkills) && tableExists(local, lSkills)) {
                    n += mergeMcMmoSkills(local, peer, lSkills, pSkills, localId, peerId);
                }
            }
        }
        return n;
    }

    private static String resolveLocalMcmmoPrefix(OfficialConfig cfg) {
        // Local mcMMO usually uses mcmmo_ regardless of root_ prefix
        return "mcmmo_";
    }

    private int upsertMcMmoUser(Connection local, String usersTable, int peerId, String user)
            throws SQLException {
        try (PreparedStatement ps = local.prepareStatement(
                "INSERT IGNORE INTO " + usersTable + " (user) VALUES (?)")) {
            ps.setString(1, user);
            return ps.executeUpdate();
        } catch (SQLException ex) {
            // Schema variants — try with id
            try (PreparedStatement ps = local.prepareStatement(
                    "INSERT IGNORE INTO " + usersTable + " (id, user) VALUES (?,?)")) {
                ps.setInt(1, peerId);
                ps.setString(2, user);
                return ps.executeUpdate();
            } catch (SQLException ignored) {
                return 0;
            }
        }
    }

    private int findMcMmoUserId(Connection local, String usersTable, String user) throws SQLException {
        try (PreparedStatement ps = local.prepareStatement(
                "SELECT id FROM " + usersTable + " WHERE user = ? LIMIT 1")) {
            ps.setString(1, user);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("id") : 0;
            }
        }
    }

    private int mergeMcMmoSkills(
            Connection local, Connection peer, String lSkills, String pSkills, int localId, int peerId)
            throws SQLException {
        List<String> cols = new ArrayList<>();
        for (String c : MCMMO_SKILLS) {
            cols.add(c);
        }
        String colList = String.join(", ", cols);
        String greatest = cols.stream()
                .map(c -> c + " = GREATEST(COALESCE(" + lSkills + "." + c + ",0), COALESCE(VALUES(" + c + "),0))")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        try (PreparedStatement ps = peer.prepareStatement(
                "SELECT " + colList + " FROM " + pSkills + " WHERE user_id = ?")) {
            ps.setInt(1, peerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                StringBuilder insert = new StringBuilder(
                        "INSERT INTO " + lSkills + " (user_id, " + colList + ") VALUES (?");
                for (int i = 0; i < cols.size(); i++) {
                    insert.append(",?");
                }
                insert.append(") ON DUPLICATE KEY UPDATE ").append(greatest);
                try (PreparedStatement up = local.prepareStatement(insert.toString())) {
                    up.setInt(1, localId);
                    for (int i = 0; i < cols.size(); i++) {
                        up.setInt(i + 2, rs.getInt(cols.get(i)));
                    }
                    return up.executeUpdate();
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().fine("mcMMO skills merge skipped: " + ex.getMessage());
            return 0;
        }
    }

    private int syncPermsNetwork(Connection local, Connection peer, OfficialConfig cfg, UUID only)
            throws SQLException {
        String lg = cfg.localPrefix() + "perms_group";
        String pg = cfg.peerPrefix() + "perms_group";
        String lgn = cfg.localPrefix() + "perms_group_node";
        String pgn = cfg.peerPrefix() + "perms_group_node";
        String lu = cfg.localPrefix() + "perms_user";
        String pu = cfg.peerPrefix() + "perms_user";
        String lun = cfg.localPrefix() + "perms_user_node";
        String pun = cfg.peerPrefix() + "perms_user_node";
        int n = 0;
        if (tableExists(peer, pg) && tableExists(local, lg)) {
            try (PreparedStatement ps = peer.prepareStatement(
                            "SELECT group_id, display, prefix, priority, updated_at FROM " + pg);
                    ResultSet rs = ps.executeQuery();
                    PreparedStatement up = local.prepareStatement(
                            """
                            INSERT INTO %s (group_id, display, prefix, priority, updated_at)
                            VALUES (?,?,?,?,?)
                            ON DUPLICATE KEY UPDATE
                              display = VALUES(display),
                              prefix = VALUES(prefix),
                              priority = VALUES(priority),
                              updated_at = GREATEST(%s.updated_at, VALUES(updated_at))
                            """
                                    .formatted(lg, lg))) {
                while (rs.next()) {
                    up.setString(1, rs.getString("group_id"));
                    up.setString(2, rs.getString("display"));
                    up.setString(3, rs.getString("prefix"));
                    up.setInt(4, rs.getInt("priority"));
                    up.setTimestamp(5, rs.getTimestamp("updated_at"));
                    up.addBatch();
                    n++;
                }
                up.executeBatch();
            }
        }
        if (tableExists(peer, pgn) && tableExists(local, lgn)) {
            try (PreparedStatement ps = peer.prepareStatement(
                            "SELECT group_id, kind, value FROM " + pgn);
                    ResultSet rs = ps.executeQuery();
                    PreparedStatement up = local.prepareStatement(
                            "INSERT IGNORE INTO " + lgn + " (group_id, kind, value) VALUES (?,?,?)")) {
                while (rs.next()) {
                    up.setString(1, rs.getString("group_id"));
                    up.setString(2, rs.getString("kind"));
                    up.setString(3, rs.getString("value"));
                    up.addBatch();
                    n++;
                }
                up.executeBatch();
            }
        }
        if (tableExists(peer, pu) && tableExists(local, lu)) {
            String sql = only == null
                    ? "SELECT uuid, username, primary_group, updated_at FROM " + pu
                    : "SELECT uuid, username, primary_group, updated_at FROM " + pu + " WHERE uuid = ?";
            try (PreparedStatement ps = peer.prepareStatement(sql)) {
                if (only != null) {
                    ps.setString(1, only.toString());
                }
                try (ResultSet rs = ps.executeQuery();
                        PreparedStatement up = local.prepareStatement(
                                """
                                INSERT INTO %s (uuid, username, primary_group, updated_at)
                                VALUES (?,?,?,?)
                                ON DUPLICATE KEY UPDATE
                                  username = VALUES(username),
                                  updated_at = GREATEST(%s.updated_at, VALUES(updated_at))
                                """
                                        .formatted(lu, lu))) {
                    while (rs.next()) {
                        up.setString(1, rs.getString("uuid"));
                        up.setString(2, rs.getString("username"));
                        up.setString(3, rs.getString("primary_group"));
                        up.setTimestamp(4, rs.getTimestamp("updated_at"));
                        up.addBatch();
                        n++;
                    }
                    up.executeBatch();
                }
            }
        }
        if (tableExists(peer, pun) && tableExists(local, lun)) {
            String sql = only == null
                    ? "SELECT uuid, scope, kind, value FROM " + pun + " WHERE scope = '*'"
                    : "SELECT uuid, scope, kind, value FROM " + pun + " WHERE scope = '*' AND uuid = ?";
            try (PreparedStatement ps = peer.prepareStatement(sql)) {
                if (only != null) {
                    ps.setString(1, only.toString());
                }
                try (ResultSet rs = ps.executeQuery();
                        PreparedStatement up = local.prepareStatement(
                                "INSERT IGNORE INTO " + lun
                                        + " (uuid, scope, kind, value) VALUES (?,?,?,?)")) {
                    while (rs.next()) {
                        up.setString(1, rs.getString("uuid"));
                        up.setString(2, "*");
                        up.setString(3, rs.getString("kind"));
                        up.setString(4, rs.getString("value"));
                        up.addBatch();
                        n++;
                    }
                    up.executeBatch();
                }
            }
        }
        return n;
    }

    private int syncActivity(Connection local, Connection peer, OfficialConfig cfg, UUID only)
            throws SQLException {
        String lt = cfg.localPrefix() + "activity_timezone";
        String pt = cfg.peerPrefix() + "activity_timezone";
        String lh = cfg.localPrefix() + "activity_hourly";
        String ph = cfg.peerPrefix() + "activity_hourly";
        int n = 0;
        if (tableExists(peer, pt) && tableExists(local, lt)) {
            String sql = only == null
                    ? "SELECT minecraft_uuid, timezone_key, source, last_ip, updated_at FROM " + pt
                    : "SELECT minecraft_uuid, timezone_key, source, last_ip, updated_at FROM " + pt
                            + " WHERE minecraft_uuid = ?";
            try (PreparedStatement ps = peer.prepareStatement(sql)) {
                if (only != null) {
                    ps.setString(1, only.toString());
                }
                try (ResultSet rs = ps.executeQuery();
                        PreparedStatement up = local.prepareStatement(
                                """
                                INSERT INTO %s (minecraft_uuid, timezone_key, source, last_ip, updated_at)
                                VALUES (?,?,?,?,?)
                                ON DUPLICATE KEY UPDATE
                                  timezone_key = IF(VALUES(updated_at) >= %s.updated_at, VALUES(timezone_key), %s.timezone_key),
                                  source = IF(VALUES(updated_at) >= %s.updated_at, VALUES(source), %s.source),
                                  last_ip = IF(VALUES(updated_at) >= %s.updated_at, VALUES(last_ip), %s.last_ip),
                                  updated_at = GREATEST(%s.updated_at, VALUES(updated_at))
                                """
                                        .formatted(lt, lt, lt, lt, lt, lt, lt, lt))) {
                    while (rs.next()) {
                        up.setString(1, rs.getString("minecraft_uuid"));
                        up.setString(2, rs.getString("timezone_key"));
                        up.setString(3, rs.getString("source"));
                        up.setString(4, rs.getString("last_ip"));
                        up.setTimestamp(5, rs.getTimestamp("updated_at"));
                        up.addBatch();
                        n++;
                    }
                    up.executeBatch();
                }
            }
        }
        if (tableExists(peer, ph) && tableExists(local, lh)) {
            String sql = only == null
                    ? "SELECT minecraft_uuid, local_hour, play_seconds FROM " + ph
                    : "SELECT minecraft_uuid, local_hour, play_seconds FROM " + ph
                            + " WHERE minecraft_uuid = ?";
            try (PreparedStatement ps = peer.prepareStatement(sql)) {
                if (only != null) {
                    ps.setString(1, only.toString());
                }
                try (ResultSet rs = ps.executeQuery();
                        PreparedStatement up = local.prepareStatement(
                                """
                                INSERT INTO %s (minecraft_uuid, local_hour, play_seconds)
                                VALUES (?,?,?)
                                ON DUPLICATE KEY UPDATE
                                  play_seconds = GREATEST(%s.play_seconds, VALUES(play_seconds))
                                """
                                        .formatted(lh, lh))) {
                    while (rs.next()) {
                        up.setString(1, rs.getString("minecraft_uuid"));
                        up.setInt(2, rs.getInt("local_hour"));
                        up.setLong(3, rs.getLong("play_seconds"));
                        up.addBatch();
                        n++;
                    }
                    up.executeBatch();
                }
            }
        }
        return n;
    }

    private int syncCmdtest(Connection local, Connection peer, OfficialConfig cfg, UUID only)
            throws SQLException {
        String lp = cfg.localPrefix() + "command_test_progress";
        String pp = cfg.peerPrefix() + "command_test_progress";
        String ld = cfg.localPrefix() + "command_test_done";
        String pd = cfg.peerPrefix() + "command_test_done";
        int n = 0;
        if (tableExists(peer, pp) && tableExists(local, lp)) {
            String sql = only == null
                    ? "SELECT minecraft_uuid, enrolled_at, completed_at, total_gold_paid, eligible_count, gold_per_command FROM "
                            + pp
                    : "SELECT minecraft_uuid, enrolled_at, completed_at, total_gold_paid, eligible_count, gold_per_command FROM "
                            + pp + " WHERE minecraft_uuid = ?";
            try (PreparedStatement ps = peer.prepareStatement(sql)) {
                if (only != null) {
                    ps.setString(1, only.toString());
                }
                try (ResultSet rs = ps.executeQuery();
                        PreparedStatement up = local.prepareStatement(
                                """
                                INSERT INTO %s (minecraft_uuid, enrolled_at, completed_at, total_gold_paid, eligible_count, gold_per_command)
                                VALUES (?,?,?,?,?,?)
                                ON DUPLICATE KEY UPDATE
                                  total_gold_paid = GREATEST(%s.total_gold_paid, VALUES(total_gold_paid)),
                                  eligible_count = GREATEST(%s.eligible_count, VALUES(eligible_count)),
                                  completed_at = COALESCE(%s.completed_at, VALUES(completed_at))
                                """
                                        .formatted(lp, lp, lp, lp))) {
                    while (rs.next()) {
                        up.setString(1, rs.getString("minecraft_uuid"));
                        up.setTimestamp(2, rs.getTimestamp("enrolled_at"));
                        up.setTimestamp(3, rs.getTimestamp("completed_at"));
                        up.setDouble(4, rs.getDouble("total_gold_paid"));
                        up.setInt(5, rs.getInt("eligible_count"));
                        up.setDouble(6, rs.getDouble("gold_per_command"));
                        up.addBatch();
                        n++;
                    }
                    up.executeBatch();
                }
            }
        }
        if (tableExists(peer, pd) && tableExists(local, ld)) {
            String sql = only == null
                    ? "SELECT minecraft_uuid, test_key, tested_at, gold_paid, fee_refunded, report_note FROM " + pd
                    : "SELECT minecraft_uuid, test_key, tested_at, gold_paid, fee_refunded, report_note FROM " + pd
                            + " WHERE minecraft_uuid = ?";
            try (PreparedStatement ps = peer.prepareStatement(sql)) {
                if (only != null) {
                    ps.setString(1, only.toString());
                }
                try (ResultSet rs = ps.executeQuery();
                        PreparedStatement up = local.prepareStatement(
                                "INSERT IGNORE INTO " + ld
                                        + " (minecraft_uuid, test_key, tested_at, gold_paid, fee_refunded, report_note)"
                                        + " VALUES (?,?,?,?,?,?)")) {
                    while (rs.next()) {
                        up.setString(1, rs.getString("minecraft_uuid"));
                        up.setString(2, rs.getString("test_key"));
                        up.setTimestamp(3, rs.getTimestamp("tested_at"));
                        up.setDouble(4, rs.getDouble("gold_paid"));
                        up.setDouble(5, rs.getDouble("fee_refunded"));
                        up.setString(6, rs.getString("report_note"));
                        up.addBatch();
                        n++;
                    }
                    up.executeBatch();
                }
            }
        }
        return n;
    }

    /** Warm link status (rootstat_players) peer ↔ peer so /discord works without CF. */
    private int syncLinkCache(Connection local, Connection peer, OfficialConfig cfg, UUID only)
            throws SQLException {
        String lp = cfg.localPrefix() + "rootstat_players";
        String pp = cfg.peerPrefix() + "rootstat_players";
        ensureLinkCacheTable(local, lp);
        ensureLinkCacheTable(peer, pp);
        int n = 0;
        n += copyLinkRows(peer, local, pp, lp, only);
        n += copyLinkRows(local, peer, lp, pp, only);
        return n;
    }

    private static void ensureLinkCacheTable(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                """
                CREATE TABLE IF NOT EXISTS %s (
                  uuid CHAR(36) PRIMARY KEY,
                  username VARCHAR(16) NOT NULL,
                  account_id VARCHAR(64) NULL,
                  email VARCHAR(255) NULL,
                  verified TINYINT(1) NOT NULL DEFAULT 0,
                  verified_at DATETIME NULL,
                  updated_at DATETIME NOT NULL,
                  INDEX idx_rootstat_account (account_id),
                  INDEX idx_rootstat_username (username)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """
                        .formatted(table))) {
            ps.executeUpdate();
        }
    }

    private static int copyLinkRows(
            Connection from, Connection to, String fromTable, String toTable, UUID only)
            throws SQLException {
        if (!tableExists(from, fromTable) || !tableExists(to, toTable)) {
            return 0;
        }
        String sql = only == null
                ? "SELECT uuid, username, account_id, email, verified, verified_at, updated_at FROM " + fromTable
                : "SELECT uuid, username, account_id, email, verified, verified_at, updated_at FROM "
                        + fromTable + " WHERE uuid = ?";
        int n = 0;
        try (PreparedStatement ps = from.prepareStatement(sql)) {
            if (only != null) {
                ps.setString(1, only.toString());
            }
            try (ResultSet rs = ps.executeQuery();
                    PreparedStatement up = to.prepareStatement(
                            """
                            INSERT INTO %s (uuid, username, account_id, email, verified, verified_at, updated_at)
                            VALUES (?,?,?,?,?,?,?)
                            ON DUPLICATE KEY UPDATE
                              username = VALUES(username),
                              account_id = COALESCE(VALUES(account_id), %s.account_id),
                              email = COALESCE(VALUES(email), %s.email),
                              verified = GREATEST(%s.verified, VALUES(verified)),
                              verified_at = COALESCE(VALUES(verified_at), %s.verified_at),
                              updated_at = GREATEST(%s.updated_at, VALUES(updated_at))
                            """
                                    .formatted(toTable, toTable, toTable, toTable, toTable, toTable))) {
                while (rs.next()) {
                    up.setString(1, rs.getString("uuid"));
                    up.setString(2, rs.getString("username"));
                    up.setString(3, rs.getString("account_id"));
                    up.setString(4, rs.getString("email"));
                    up.setBoolean(5, rs.getBoolean("verified"));
                    up.setTimestamp(6, rs.getTimestamp("verified_at"));
                    up.setTimestamp(7, rs.getTimestamp("updated_at"));
                    up.addBatch();
                    n++;
                }
                up.executeBatch();
            }
        }
        return n;
    }

    private static String lookupUsername(Connection local, String playtimeTable, UUID uuid)
            throws SQLException {
        if (!tableExists(local, playtimeTable)) {
            return null;
        }
        try (PreparedStatement ps = local.prepareStatement(
                "SELECT username FROM " + playtimeTable + " WHERE uuid = ? ORDER BY seconds DESC LIMIT 1")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static boolean columnExists(Connection c, String table, String column) throws SQLException {
        try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, table, column)) {
            if (rs.next()) {
                return true;
            }
        }
        try (ResultSet rs = c.getMetaData().getColumns(
                c.getCatalog(), null, table.toLowerCase(Locale.ROOT), column)) {
            if (rs.next()) {
                return true;
            }
        }
        try (ResultSet rs = c.getMetaData().getColumns(
                c.getCatalog(), null, table, column.toLowerCase(Locale.ROOT))) {
            return rs.next();
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (ResultSet rs = c.getMetaData().getTables(c.getCatalog(), null, table, null)) {
            if (rs.next()) {
                return true;
            }
        }
        // Some hosts return lowercase only
        try (ResultSet rs = c.getMetaData().getTables(c.getCatalog(), null, table.toLowerCase(Locale.ROOT), null)) {
            return rs.next();
        }
    }

    public record SyncStats(int rowsTouched, int errors, String error) {}
}
