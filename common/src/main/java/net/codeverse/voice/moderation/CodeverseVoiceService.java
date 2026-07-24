package net.codeverse.voice.moderation;

import net.codeverse.api.identity.Identity;
import net.codeverse.api.identity.IdentityService;
import net.codeverse.api.voice.VoiceAccess;
import net.codeverse.api.voice.VoiceRestriction;
import net.codeverse.api.voice.VoiceService;
import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.model.VoiceBan;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * The network facing view of voice restrictions.
 *
 * This is what a scoreboard on the lobby or a staff panel on another server
 * talks to. It wraps the same {@link VoiceBanService} the enforcing server
 * uses, so a restriction applied on the SMP is the restriction a lobby reports,
 * because both read the same rows keyed by the same internal id.
 *
 * The contract's access evaluation stops at trust tier and restriction. The
 * permission half of a speaking decision needs the live platform player and
 * runs on the packet path, so it stays in the platform's own call into
 * {@link VoiceBanService#evaluate}. Exposing it here would mean either dragging
 * a platform permission check into shared code or answering the question
 * without it, and answering without it would be wrong on the one server that
 * matters.
 *
 * @see VoiceService for the threading and failure contract this honours
 */
public final class CodeverseVoiceService implements VoiceService {

    private final VoiceBanService bans;
    private final IdentityService identities;
    private final PluginConfig.Access access;
    private final boolean linkageAvailable;
    private final boolean enforcing;
    private final Executor executor;

    public CodeverseVoiceService(VoiceBanService bans,
                                 IdentityService identities,
                                 PluginConfig.Access access,
                                 boolean linkageAvailable,
                                 boolean enforcing,
                                 Executor executor) {
        this.bans = bans;
        this.identities = identities;
        this.access = access;
        this.linkageAvailable = linkageAvailable;
        this.enforcing = enforcing;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Optional<VoiceRestriction>> activeRestriction(UUID internalId) {
        return async(() -> bans.activeBan(internalId)
                .filter(ban -> ban.isEnforceable(System.currentTimeMillis()))
                .map(VoiceBan::toApi));
    }

    @Override
    public Optional<VoiceRestriction> cachedRestriction(UUID internalId) {
        return bans.cachedBan(internalId)
                .filter(ban -> ban.isEnforceable(System.currentTimeMillis()))
                .map(VoiceBan::toApi);
    }

    @Override
    public CompletableFuture<VoiceRestriction> restrict(UUID internalId,
                                                        String reason,
                                                        UUID issuedBy,
                                                        Optional<Duration> duration) {
        long millis = duration.map(Duration::toMillis).orElse(0L);
        return async(() -> {
            try {
                return bans.ban(internalId, reason, issuedBy, millis).toApi();
            } catch (SQLException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> lift(UUID internalId, UUID liftedBy) {
        return async(() -> {
            try {
                return bans.unban(internalId, liftedBy);
            } catch (SQLException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    @Override
    public CompletableFuture<List<VoiceRestriction>> history(UUID internalId, int limit) {
        return async(() -> {
            try {
                return bans.history(internalId, limit).stream().map(VoiceBan::toApi).toList();
            } catch (SQLException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    /**
     * The identity and restriction half of a speaking decision.
     *
     * The permission half is deliberately absent, because it cannot be
     * answered here. A caller on the enforcing server combines this with the
     * platform permission check; a caller elsewhere is asking a reporting
     * question, for which tier and restriction are the whole answer. Either
     * way an unresolved identity is UNKNOWN_IDENTITY, so the fail closed order
     * holds even without the permission term.
     */
    @Override
    public CompletableFuture<VoiceAccess> evaluate(UUID minecraftId) {
        return identities.byMinecraftId(minecraftId).thenApply(identity -> {
            if (identity.isEmpty()) {
                return access.requireVerifiedOrigin && linkageAvailable
                        ? VoiceAccess.UNKNOWN_IDENTITY
                        : VoiceAccess.ALLOWED;
            }
            return VoiceBanService.decide(
                    access,
                    identity.map(Identity::tier).orElse(null),
                    true,
                    linkageAvailable,
                    true,
                    bans.activeBan(identity.get().internalId())
                            .map(ban -> ban.isEnforceable(System.currentTimeMillis()))
                            .orElse(false));
        });
    }

    @Override
    public Optional<VoiceAccess> cachedEvaluate(UUID minecraftId) {
        Optional<Identity> identity = identities.cachedByMinecraftId(minecraftId);
        if (identity.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(VoiceBanService.decide(
                access,
                identity.get().tier(),
                true,
                linkageAvailable,
                true,
                bans.activeBan(identity.get().internalId())
                        .map(ban -> ban.isEnforceable(System.currentTimeMillis()))
                        .orElse(false)));
    }

    @Override
    public boolean isEnforcing() {
        return enforcing;
    }

    private <T> CompletableFuture<T> async(java.util.function.Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, executor);
    }
}
