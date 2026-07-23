package net.codeverse.voice.storage;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.codeverse.voice.config.PluginConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the network internal identity behind a Minecraft uuid.
 *
 * Reads the accounts table owned by the authentication plugin. Read only by
 * design: two plugins writing the same identity rows would eventually disagree,
 * and the authoritative writer is the one that authenticates people.
 *
 * When the table is unavailable the lookup degrades to treating the Minecraft
 * uuid as the identity. That keeps voice working during an outage, but it also
 * means restrictions stop following people across linked accounts, so the
 * degradation is reported rather than silent.
 */
public final class IdentityLookup {

    private final Database database;
    private final PluginConfig.Identity settings;
    private final Cache<UUID, Resolved> cache;
    private volatile boolean tableAvailable;

    public IdentityLookup(Database database, PluginConfig.Identity settings) {
        this.database = database;
        this.settings = settings;
        this.cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(Math.max(30, settings.cacheSeconds)))
                .build();
        this.tableAvailable = settings.useAuthPluginIdentities;
    }

    /** Verifies the accounts table exists, so the fallback is reported at startup rather than mid incident. */
    public boolean probe() {
        if (!settings.useAuthPluginIdentities) {
            tableAvailable = false;
            return false;
        }
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM " + settings.accountsTable + " LIMIT 1")) {
            statement.executeQuery().close();
            tableAvailable = true;
        } catch (SQLException missing) {
            tableAvailable = false;
        }
        return tableAvailable;
    }

    public boolean isUsingAuthIdentities() {
        return tableAvailable;
    }

    /**
     * Returns the internal identity and stored trust tier for a connection.
     * Falls back to the Minecraft uuid with an unknown tier when the accounts
     * table is not in use.
     */
    public Resolved resolve(UUID minecraftId) {
        if (!tableAvailable) {
            return new Resolved(minecraftId, null, false);
        }
        Resolved cached = cache.getIfPresent(minecraftId);
        if (cached != null) {
            return cached;
        }
        String sql = "SELECT internal_id, tier FROM " + settings.accountsTable + " WHERE minecraft_id = ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, VoiceBanRepository.toBytes(minecraftId));
            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    Resolved resolved = new Resolved(
                            VoiceBanRepository.fromBytes(results.getBytes("internal_id")),
                            results.getString("tier"),
                            true);
                    cache.put(minecraftId, resolved);
                    return resolved;
                }
            }
        } catch (SQLException failure) {
            // A failed lookup is not cached, so a transient outage does not
            // pin an account to the fallback identity for the cache lifetime.
            return new Resolved(minecraftId, null, false);
        }
        Resolved unknown = new Resolved(minecraftId, null, false);
        cache.put(minecraftId, unknown);
        return unknown;
    }

    /**
     * The cached identity only, never touching storage. Returns null when the
     * identity has not been resolved yet, so main thread callers such as
     * placeholders can render a waiting state instead of blocking.
     */
    public Resolved resolveCached(UUID minecraftId) {
        if (!tableAvailable) {
            return new Resolved(minecraftId, null, false);
        }
        return cache.getIfPresent(minecraftId);
    }

    public void invalidate(UUID minecraftId) {
        cache.invalidate(minecraftId);
    }

    public Optional<UUID> internalIdOf(UUID minecraftId) {
        Resolved resolved = resolve(minecraftId);
        return resolved.known() ? Optional.of(resolved.internalId()) : Optional.empty();
    }

    /**
     * @param internalId identity to key restrictions against
     * @param tier       stored trust tier, null when unknown
     * @param known      whether the identity came from the accounts table
     */
    public record Resolved(UUID internalId, String tier, boolean known) {
    }
}
