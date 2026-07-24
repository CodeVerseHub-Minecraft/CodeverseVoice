package net.codeverse.voice.moderation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.codeverse.api.identity.Identity;
import net.codeverse.api.identity.IdentityService;
import net.codeverse.api.identity.TrustTier;
import net.codeverse.api.voice.VoiceAccess;
import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.model.VoiceBan;
import net.codeverse.voice.storage.VoiceBanRepository;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Decides whether someone may speak, and applies restrictions.
 *
 * The decision runs on every microphone packet, fifty times a second per
 * speaker, so it must never touch the database on the hot path. Active
 * restrictions are cached and the cache is invalidated on change, both locally
 * and across servers. Identity comes from the shared API rather than a private
 * lookup, so the same rows the authentication plugin writes are the rows read
 * here, and a person's linked accounts resolve to one identity.
 *
 * The evaluation order matters. Identity is resolved first because every other
 * check depends on knowing who this is, and an unresolvable identity is denied
 * rather than waved through. That mirrors the fail closed stance the
 * authentication layer takes, for the same reason: a capability granted to an
 * unknown identity cannot be taken back from the right person.
 */
public final class VoiceBanService {

    private final VoiceBanRepository repository;
    private final IdentityService identities;
    private final PluginConfig.Access access;
    private final boolean linkageAvailable;
    private final Cache<UUID, Optional<VoiceBan>> banCache;

    public VoiceBanService(VoiceBanRepository repository,
                           IdentityService identities,
                           boolean linkageAvailable,
                           PluginConfig.Access access) {
        this.repository = repository;
        this.identities = identities;
        this.linkageAvailable = linkageAvailable;
        this.access = access;
        this.banCache = Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
    }

    /**
     * Evaluates whether a connection may speak, on the calling thread.
     *
     * Reads only what is already cached. The voice packet path calls this
     * fifty times a second and cannot afford a database round trip or a future
     * to complete, so an identity that has not been resolved yet is reported
     * as unknown and therefore denied. The session listener warms the cache on
     * join precisely so that this path finds an answer waiting.
     *
     * @param minecraftId     the connecting account
     * @param permissionCheck tests a permission node against that account
     */
    public VoiceAccess evaluate(UUID minecraftId, Predicate<String> permissionCheck) {
        Optional<Identity> identity = identities.cachedByMinecraftId(minecraftId);
        UUID internalId = identity.map(Identity::internalId).orElse(minecraftId);
        boolean restricted = activeBan(internalId)
                .map(ban -> ban.isEnforceable(System.currentTimeMillis()))
                .orElse(false);

        return decide(
                access,
                identity.map(Identity::tier).orElse(null),
                identity.isPresent(),
                linkageAvailable,
                permissionCheck.test(access.speakPermission),
                restricted);
    }

    /**
     * The access decision, expressed as a pure function of the facts.
     *
     * Kept free of storage and platform types so the order of checks can be
     * verified directly. That order is the security property worth protecting:
     * an unresolved identity is refused before anything else is considered,
     * because every later check depends on knowing who this is, and a
     * capability handed to an unknown identity cannot be taken back from the
     * right person afterwards.
     *
     * @param tier               trust tier, null when the account is unknown
     * @param identityKnown      whether the identity was resolved
     * @param usingAuthIdentity  whether identity linkage is available at all
     * @param hasSpeakPermission whether the speak permission is held
     * @param hasEnforceableBan  whether an active restriction applies
     */
    public static VoiceAccess decide(PluginConfig.Access access,
                                     TrustTier tier,
                                     boolean identityKnown,
                                     boolean usingAuthIdentity,
                                     boolean hasSpeakPermission,
                                     boolean hasEnforceableBan) {
        if (access.requireVerifiedOrigin && usingAuthIdentity && !identityKnown) {
            return VoiceAccess.UNKNOWN_IDENTITY;
        }
        if (access.requireVerifiedOrigin && tier != null && !isTrustedTier(access, tier)) {
            return VoiceAccess.UNTRUSTED;
        }
        if (!hasSpeakPermission) {
            return VoiceAccess.NO_PERMISSION;
        }
        if (hasEnforceableBan) {
            return VoiceAccess.RESTRICTED;
        }
        return VoiceAccess.ALLOWED;
    }

    private static boolean isTrustedTier(PluginConfig.Access access, TrustTier tier) {
        String normalised = tier.name();
        for (String trusted : access.trustedTiers) {
            if (trusted.toUpperCase(Locale.ROOT).equals(normalised)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The cached active restriction for an identity. A restriction that has
     * lapsed is retired here rather than waiting for the sweep, so the person
     * regains voice the moment it expires.
     */
    public Optional<VoiceBan> activeBan(UUID internalId) {
        Optional<VoiceBan> cached = banCache.getIfPresent(internalId);
        if (cached != null) {
            if (cached.isPresent() && cached.get().hasExpired(System.currentTimeMillis())) {
                banCache.invalidate(internalId);
            } else {
                return cached;
            }
        }
        Optional<VoiceBan> loaded;
        try {
            loaded = repository.findActive(internalId);
        } catch (SQLException failure) {
            // A database that cannot be read must not become a way to speak
            // while restricted, so an unreadable restriction is treated as
            // present. The caller reports this distinctly.
            return Optional.of(new VoiceBan(internalId, "storage unavailable", null,
                    System.currentTimeMillis(), 0L, true));
        }
        if (loaded.isPresent() && loaded.get().hasExpired(System.currentTimeMillis())) {
            try {
                repository.lift(internalId, null);
            } catch (SQLException ignored) {
                // The sweep retires it later; failing to retire early only
                // costs one redundant query on the next evaluation.
            }
            loaded = Optional.empty();
        }
        banCache.put(internalId, loaded);
        return loaded;
    }

    public VoiceBan ban(UUID internalId, String reason, UUID issuedBy, long durationMillis) throws SQLException {
        long now = System.currentTimeMillis();
        long expiresAt = durationMillis <= 0L ? 0L : now + durationMillis;
        VoiceBan ban = new VoiceBan(internalId, reason, issuedBy, now, expiresAt, true);
        repository.insert(ban);
        repository.audit(issuedBy, internalId, "BAN",
                (expiresAt == 0L ? "permanent" : "expires " + expiresAt) + ": " + reason);
        banCache.invalidate(internalId);
        return ban;
    }

    public boolean unban(UUID internalId, UUID liftedBy) throws SQLException {
        int affected = repository.lift(internalId, liftedBy);
        repository.audit(liftedBy, internalId, "UNBAN", affected + " restriction(s) lifted");
        banCache.invalidate(internalId);
        return affected > 0;
    }

    public List<VoiceBan> history(UUID internalId, int limit) throws SQLException {
        return repository.history(internalId, limit);
    }

    /**
     * The cached restriction only, never touching storage.
     *
     * Used by placeholders, which resolve on the main thread once per tick per
     * player. A database read on that path would stall the server, so an
     * uncached identity reports nothing rather than blocking to find out.
     */
    public Optional<VoiceBan> cachedBan(UUID internalId) {
        Optional<VoiceBan> cached = banCache.getIfPresent(internalId);
        return cached == null ? Optional.empty() : cached;
    }

    /** Warms the cache off the main thread so placeholders have something to read. */
    public void preload(UUID internalId) {
        activeBan(internalId);
    }

    /** Drops a cached decision, used when another server reports a change. */
    public void invalidate(UUID internalId) {
        banCache.invalidate(internalId);
    }

    public void invalidateAll() {
        banCache.invalidateAll();
    }

    public int sweepExpired() {
        try {
            int retired = repository.retireExpired();
            if (retired > 0) {
                banCache.invalidateAll();
            }
            return retired;
        } catch (SQLException failure) {
            return 0;
        }
    }
}
