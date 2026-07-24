package net.codeverse.voice.storage;

import net.codeverse.api.identity.Identity;
import net.codeverse.api.identity.IdentityService;
import net.codeverse.api.identity.TrustTier;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resolver's job is to turn an async, sometimes uncached identity service
 * into the synchronous cache first view the voice paths need, without changing
 * what a caller learns. A stub service stands in for storage so the adapter's
 * own logic is what is under test.
 */
class IdentityResolverTest {

    /** An identity service whose cache and backing store can be set independently. */
    private static final class StubService implements IdentityService {
        private final Map<UUID, Identity> cached = new ConcurrentHashMap<>();
        private final Map<UUID, Identity> stored = new ConcurrentHashMap<>();
        private volatile boolean linkage = true;

        void putCached(Identity identity) {
            cached.put(identity.minecraftId(), identity);
            stored.put(identity.minecraftId(), identity);
        }

        void putStoredOnly(Identity identity) {
            stored.put(identity.minecraftId(), identity);
        }

        @Override public CompletableFuture<Optional<Identity>> byMinecraftId(UUID id) {
            Identity found = stored.get(id);
            if (found != null) {
                cached.put(id, found);
            }
            return CompletableFuture.completedFuture(Optional.ofNullable(found));
        }
        @Override public CompletableFuture<Optional<Identity>> byUsername(String u) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        @Override public CompletableFuture<Optional<Identity>> byInternalId(UUID id) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        @Override public CompletableFuture<Optional<Identity>> byDiscordId(String d) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        @Override public CompletableFuture<List<Identity>> linkedAccounts(UUID id) {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public Optional<Identity> cachedByMinecraftId(UUID id) {
            return Optional.ofNullable(cached.get(id));
        }
        @Override public CompletableFuture<Void> preload(Collection<UUID> ids) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void invalidate(UUID id) {
            cached.remove(id);
        }
        @Override public boolean isLinkageAvailable() {
            return linkage;
        }
    }

    private static Identity identity(UUID minecraftId, TrustTier tier) {
        return Identity.builder(UUID.randomUUID(), minecraftId, "Player", tier).build();
    }

    @Test
    void resolvesACachedIdentityWithoutTouchingStorage() {
        StubService service = new StubService();
        UUID mc = UUID.randomUUID();
        Identity id = identity(mc, TrustTier.PREMIUM);
        service.putCached(id);

        IdentityResolver resolver = new IdentityResolver(service, true);
        IdentityResolver.Resolved resolved = resolver.resolve(mc);

        assertTrue(resolved.known());
        assertEquals(id.internalId(), resolved.internalId());
        assertEquals(TrustTier.PREMIUM, resolved.tier());
    }

    @Test
    void fallsBackToMinecraftIdWhenNothingCached() {
        StubService service = new StubService();
        UUID mc = UUID.randomUUID();
        service.putStoredOnly(identity(mc, TrustTier.PREMIUM));

        IdentityResolver resolver = new IdentityResolver(service, true);
        IdentityResolver.Resolved resolved = resolver.resolve(mc);

        // resolve() is cache only, so an identity that exists but has not been
        // warmed reads as the fallback rather than blocking to fetch it.
        assertFalse(resolved.known());
        assertEquals(mc, resolved.internalId());
        assertNull(resolved.tier());
    }

    @Test
    void cachedFormReturnsNullRatherThanFabricatingAFallback() {
        StubService service = new StubService();
        UUID mc = UUID.randomUUID();

        IdentityResolver resolver = new IdentityResolver(service, true);

        // resolveCached distinguishes "not known yet" from "unverified", which
        // a placeholder renders as waiting rather than as a cracked account.
        assertNull(resolver.resolveCached(mc));
    }

    @Test
    void asyncFormReadsThroughAndWarmsTheCache() throws Exception {
        StubService service = new StubService();
        UUID mc = UUID.randomUUID();
        service.putStoredOnly(identity(mc, TrustTier.BEDROCK));

        IdentityResolver resolver = new IdentityResolver(service, true);
        IdentityResolver.Resolved resolved = resolver.resolveAsync(mc).get();

        assertTrue(resolved.known());
        assertEquals(TrustTier.BEDROCK, resolved.tier());
        // Having read through, the cache form now answers too.
        assertEquals(TrustTier.BEDROCK, resolver.resolveCached(mc).tier());
    }

    @Test
    void internalIdIsPresentOnlyWhenTheIdentityIsKnown() {
        StubService service = new StubService();
        UUID mc = UUID.randomUUID();
        Identity id = identity(mc, TrustTier.PREMIUM);
        service.putCached(id);

        IdentityResolver resolver = new IdentityResolver(service, true);

        assertEquals(Optional.of(id.internalId()), resolver.internalIdOf(mc));
        assertEquals(Optional.empty(), resolver.internalIdOf(UUID.randomUUID()));
    }

    @Test
    void everythingFallsBackWhenLinkageIsUnavailable() {
        StubService service = new StubService();
        UUID mc = UUID.randomUUID();
        service.putCached(identity(mc, TrustTier.PREMIUM));

        // Constructed as unavailable: even a cached identity is ignored,
        // because the whole accounts table is considered absent.
        IdentityResolver resolver = new IdentityResolver(service, false);

        assertFalse(resolver.isUsingAuthIdentities());
        IdentityResolver.Resolved resolved = resolver.resolve(mc);
        assertFalse(resolved.known());
        assertEquals(mc, resolved.internalId());
    }

    @Test
    void reportsLinkageLostWhenTheServiceLosesIt() {
        StubService service = new StubService();
        IdentityResolver resolver = new IdentityResolver(service, true);
        assertTrue(resolver.isUsingAuthIdentities());

        service.linkage = false;
        assertFalse(resolver.isUsingAuthIdentities(),
                "a service that reports linkage lost must be reflected, not cached as available");
    }
}
