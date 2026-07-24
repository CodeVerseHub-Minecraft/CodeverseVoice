package net.codeverse.voice.moderation;

import net.codeverse.api.identity.TrustTier;
import net.codeverse.api.voice.VoiceAccess;
import net.codeverse.api.voice.VoiceRestriction;
import net.codeverse.jdbc.JdbcIdentityService;
import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.storage.Database;
import net.codeverse.voice.storage.VoiceBanRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The API facing voice service against real storage.
 *
 * Runs against a real database because what it exposes, restrictions keyed by
 * internal id and an access decision that fails closed on an unknown identity,
 * is a property of the rows and the SQL, not of the wrapper. Skips rather than
 * fails where no database is reachable, so a missing local service is not read
 * as a defect.
 */
class CodeverseVoiceServiceTest {

    private Database database;
    private JdbcIdentityService jdbc;
    private ExecutorService executor;
    private CodeverseVoiceService service;
    private VoiceBanService bans;

    private static String url() {
        return System.getenv().getOrDefault("CODEVERSE_TEST_JDBC_URL",
                "jdbc:mysql://127.0.0.1:3306/codeverse?useSSL=false&characterEncoding=utf8");
    }

    @BeforeEach
    void setUp() throws SQLException {
        PluginConfig.Storage storage = new PluginConfig.Storage();
        storage.jdbcUrl = url();
        storage.username = System.getenv().getOrDefault("CODEVERSE_TEST_DB_USER", "codeverse");
        storage.password = System.getenv().getOrDefault("CODEVERSE_TEST_DB_PASSWORD", "codeverse");
        storage.driverClassName = "com.mysql.cj.jdbc.Driver";
        storage.tablePrefix = "vtest_";

        try {
            database = new Database(storage);
            try (Connection connection = database.connection()) {
                connection.isValid(2);
            }
        } catch (RuntimeException | SQLException unreachable) {
            if (database != null) {
                database.close();
            }
            assumeTrue(false, "No test database reachable at " + url() + ", skipping");
        }

        database.applySchema();
        createAccountsTable();

        PluginConfig.Access access = new PluginConfig.Access();
        access.requireVerifiedOrigin = true;
        access.trustedTiers = List.of("PREMIUM", "BEDROCK", "DISCORD_LINKED");

        executor = Executors.newVirtualThreadPerTaskExecutor();
        jdbc = new JdbcIdentityService(database.dataSource(), executor, "vtest_accounts", Duration.ofMinutes(5));
        jdbc.probe();
        bans = new VoiceBanService(new VoiceBanRepository(database), jdbc, true, access);
        service = new CodeverseVoiceService(bans, jdbc, access, true, true, executor);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (database != null) {
            try (Connection connection = database.connection(); Statement statement = connection.createStatement()) {
                for (String table : new String[]{"voice_bans", "voice_captures", "voice_audit", "accounts"}) {
                    statement.executeUpdate("DROP TABLE IF EXISTS " + database.table(table));
                }
            } catch (SQLException ignored) {
                // A leftover test table is not worth failing a run over.
            }
            database.close();
        }
    }

    private void createAccountsTable() throws SQLException {
        try (Connection connection = database.connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS vtest_accounts (
                        minecraft_id BINARY(16) PRIMARY KEY,
                        internal_id BINARY(16) NOT NULL,
                        username VARCHAR(32) NOT NULL,
                        username_lower VARCHAR(32) NOT NULL,
                        tier VARCHAR(32) NOT NULL,
                        password_hash VARCHAR(255) NULL,
                        totp_secret VARCHAR(255) NULL,
                        registered_at BIGINT NOT NULL DEFAULT 0,
                        last_login_at BIGINT NOT NULL DEFAULT 0,
                        discord_id VARCHAR(32) NULL
                    )
                    """);
        }
    }

    private UUID seedAccount(String username, TrustTier tier) throws SQLException {
        UUID internalId = UUID.randomUUID();
        UUID minecraftId = UUID.randomUUID();
        try (Connection connection = database.connection();
             var statement = connection.prepareStatement(
                     "INSERT INTO vtest_accounts (minecraft_id, internal_id, username, username_lower, tier) "
                             + "VALUES (?, ?, ?, ?, ?)")) {
            statement.setBytes(1, JdbcIdentityService.toBytes(minecraftId));
            statement.setBytes(2, JdbcIdentityService.toBytes(internalId));
            statement.setString(3, username);
            statement.setString(4, username.toLowerCase());
            statement.setString(5, tier.name());
            statement.executeUpdate();
        }
        return minecraftId;
    }

    @Test
    void restrictsLiftsAndReportsHistory() throws Exception {
        UUID internalId = UUID.randomUUID();

        VoiceRestriction applied = service.restrict(internalId, "spamming", null, Optional.of(Duration.ofHours(2))).get();
        assertEquals(internalId, applied.internalId());
        assertTrue(applied.active());
        assertFalse(applied.isPermanent());

        assertTrue(service.activeRestriction(internalId).get().isPresent());

        assertTrue(service.lift(internalId, null).get());
        assertTrue(service.activeRestriction(internalId).get().isEmpty(),
                "a lifted restriction is no longer active");

        List<VoiceRestriction> history = service.history(internalId, 10).get();
        assertEquals(1, history.size(), "the lifted restriction is retained for audit");
        assertFalse(history.get(0).active());
    }

    @Test
    void restrictionFollowsTheInternalIdNotTheAccount() throws Exception {
        UUID internalId = UUID.randomUUID();
        service.restrict(internalId, "harassment", null, Optional.empty()).get();

        Optional<VoiceRestriction> restriction = service.activeRestriction(internalId).get();
        assertTrue(restriction.isPresent());
        assertTrue(restriction.get().isPermanent());
        assertEquals(internalId, restriction.get().internalId());
    }

    @Test
    void evaluateRefusesAnUnknownIdentity() throws Exception {
        // No account seeded, so the identity cannot be resolved. The fail
        // closed order makes that a refusal rather than an oversight.
        assertEquals(VoiceAccess.UNKNOWN_IDENTITY, service.evaluate(UUID.randomUUID()).get());
    }

    @Test
    void evaluateAllowsAVerifiedUnrestrictedAccount() throws Exception {
        UUID minecraftId = seedAccount("Elchi", TrustTier.PREMIUM);
        assertEquals(VoiceAccess.ALLOWED, service.evaluate(minecraftId).get());
    }

    @Test
    void evaluateRefusesACrackedAccount() throws Exception {
        UUID minecraftId = seedAccount("Guest", TrustTier.CRACKED);
        assertEquals(VoiceAccess.UNTRUSTED, service.evaluate(minecraftId).get());
    }

    @Test
    void evaluateReportsARestrictedAccountAsRestricted() throws Exception {
        UUID minecraftId = seedAccount("Loud", TrustTier.PREMIUM);
        UUID internalId = jdbc.byMinecraftId(minecraftId).get().orElseThrow().internalId();
        service.restrict(internalId, "spam", null, Optional.empty()).get();

        assertEquals(VoiceAccess.RESTRICTED, service.evaluate(minecraftId).get());
    }

    @Test
    void aBackendServiceReportsThatItEnforces() {
        assertTrue(service.isEnforcing());
    }
}
