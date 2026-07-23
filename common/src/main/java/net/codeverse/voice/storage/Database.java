package net.codeverse.voice.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.codeverse.voice.config.PluginConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Connection pool and schema management.
 *
 * Shares the network database with the other Codeverse plugins. Tables are
 * prefixed rather than isolated so that a voice restriction and an account can
 * be joined in a single query when staff need the full picture on someone.
 */
public final class Database implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final String prefix;

    public Database(PluginConfig.Storage settings) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(settings.jdbcUrl);
        hikari.setUsername(settings.username);
        hikari.setPassword(settings.password);
        if (settings.driverClassName != null && !settings.driverClassName.isBlank()) {
            hikari.setDriverClassName(settings.driverClassName);
        }
        hikari.setMaximumPoolSize(settings.maximumPoolSize);
        hikari.setMinimumIdle(settings.minimumIdle);
        hikari.setConnectionTimeout(settings.connectionTimeoutMillis);
        hikari.setPoolName("CodeverseVoice");
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        hikari.addDataSourceProperty("prepStmtCacheSize", "150");
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikari.addDataSourceProperty("useServerPrepStmts", "true");
        this.dataSource = new HikariDataSource(hikari);
        this.prefix = settings.tablePrefix;
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    public String table(String name) {
        return prefix + name;
    }

    public void applySchema() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                      id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      internal_id   BINARY(16)   NOT NULL,
                      reason        VARCHAR(255) NOT NULL,
                      issued_by     BINARY(16)   NULL,
                      issued_at     BIGINT       NOT NULL,
                      expires_at    BIGINT       NOT NULL DEFAULT 0,
                      active        TINYINT(1)   NOT NULL DEFAULT 1,
                      lifted_by     BINARY(16)   NULL,
                      lifted_at     BIGINT       NOT NULL DEFAULT 0,
                      KEY idx_internal_active (internal_id, active),
                      KEY idx_expires (expires_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(table("voice_bans")));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                      id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      internal_id   BINARY(16)   NOT NULL,
                      captured_by   BINARY(16)   NULL,
                      captured_at   BIGINT       NOT NULL,
                      duration_ms   INT          NOT NULL,
                      file_name     VARCHAR(255) NOT NULL,
                      note          VARCHAR(512) NULL,
                      expires_at    BIGINT       NOT NULL DEFAULT 0,
                      KEY idx_internal (internal_id),
                      KEY idx_expires (expires_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(table("voice_captures")));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                      id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      actor_id      BINARY(16)   NULL,
                      target_id     BINARY(16)   NULL,
                      action        VARCHAR(32)  NOT NULL,
                      detail        VARCHAR(512) NULL,
                      created_at    BIGINT       NOT NULL,
                      KEY idx_created (created_at),
                      KEY idx_target (target_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(table("voice_audit")));
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
