package net.codeverse.voice.storage;

import net.codeverse.api.identity.Identity;
import net.codeverse.api.identity.IdentityService;
import net.codeverse.api.identity.TrustTier;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A synchronous, cache first view of the shared identity service.
 *
 * The API's {@link IdentityService} is asynchronous by contract, because a
 * lookup may touch storage and must never be joined on a server thread. Most
 * of this plugin's callers already hold a warmed cache and want an answer now:
 * the voice packet path, placeholders, the session listener that warms on
 * join. This adapter gives them that answer from the cache, and exposes the
 * asynchronous form for the one caller that legitimately needs to wait.
 *
 * It replaces the plugin's former hand written accounts lookup. The behaviour
 * is deliberately the same, including the fallback: when linkage is
 * unavailable the Minecraft uuid stands in as the identity, so voice keeps
 * working through an outage, at the known cost that restrictions stop
 * following people across linked accounts until it recovers. That degradation
 * is reported at startup rather than discovered mid incident.
 */
public final class IdentityResolver {

    private final IdentityService service;
    private final boolean linkageAvailable;

    public IdentityResolver(IdentityService service, boolean linkageAvailable) {
        this.service = service;
        this.linkageAvailable = linkageAvailable;
    }

    /** Whether identities are backed by the accounts table rather than the fallback. */
    public boolean isUsingAuthIdentities() {
        return linkageAvailable && service.isLinkageAvailable();
    }

    /**
     * The resolved identity from cache, or the fallback when nothing is
     * cached. Never blocks, so it is safe from any thread including a tick.
     */
    public Resolved resolve(UUID minecraftId) {
        if (!isUsingAuthIdentities()) {
            return new Resolved(minecraftId, null, false);
        }
        return service.cachedByMinecraftId(minecraftId)
                .map(Resolved::of)
                .orElseGet(() -> new Resolved(minecraftId, null, false));
    }

    /**
     * The cached identity, or null when it has not been resolved yet.
     *
     * Distinct from {@link #resolve} in that it does not fabricate a fallback:
     * a null here means "not known yet", which a placeholder renders as a
     * waiting state rather than as an unverified account.
     */
    public Resolved resolveCached(UUID minecraftId) {
        if (!isUsingAuthIdentities()) {
            return new Resolved(minecraftId, null, false);
        }
        return service.cachedByMinecraftId(minecraftId).map(Resolved::of).orElse(null);
    }

    /** The internal id, present only when the identity is actually known. */
    public Optional<UUID> internalIdOf(UUID minecraftId) {
        Resolved resolved = resolve(minecraftId);
        return resolved.known() ? Optional.of(resolved.internalId()) : Optional.empty();
    }

    /**
     * Resolves through storage when the cache misses, warming it as a side
     * effect. The one asynchronous entry point, for the session listener that
     * runs on join and can afford to wait off thread.
     */
    public CompletableFuture<Resolved> resolveAsync(UUID minecraftId) {
        if (!isUsingAuthIdentities()) {
            return CompletableFuture.completedFuture(new Resolved(minecraftId, null, false));
        }
        return service.byMinecraftId(minecraftId)
                .thenApply(identity -> identity.map(Resolved::of)
                        .orElseGet(() -> new Resolved(minecraftId, null, false)));
    }

    /**
     * Resolves through storage, blocking until an answer is available.
     *
     * For callers already off the main thread that must have the real identity
     * rather than a fallback: a staff command keying a restriction, for one. A
     * restriction written against the fallback Minecraft uuid would not follow
     * the person across their accounts, which is the whole reason restrictions
     * key on the internal id. Never call this from a server thread; the cache
     * first {@link #resolve} exists for that.
     */
    public Resolved resolveThrough(UUID minecraftId) {
        return resolveAsync(minecraftId).join();
    }

    public void invalidate(UUID minecraftId) {
        service.invalidate(minecraftId);
    }

    /**
     * The internal id and trust tier behind a connection.
     *
     * @param internalId identity to key restrictions against, or the Minecraft
     *                   uuid itself when the identity is unknown
     * @param tier       trust tier, null when the account is unknown
     * @param known      whether the identity was resolved rather than fallen back to
     */
    public record Resolved(UUID internalId, TrustTier tier, boolean known) {

        static Resolved of(Identity identity) {
            return new Resolved(identity.internalId(), identity.tier(), true);
        }
    }
}
