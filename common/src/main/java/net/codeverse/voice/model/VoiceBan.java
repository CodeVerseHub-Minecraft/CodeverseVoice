package net.codeverse.voice.model;

import net.codeverse.api.voice.VoiceRestriction;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A voice restriction as it is stored, distinct from how it is exposed.
 *
 * The shared API speaks {@link VoiceRestriction}, which uses Instant and
 * Optional. Storage predates that contract and speaks epoch millis with a
 * sentinel zero for permanent and a nullable issuer, which is what the schema
 * holds and what the repository reads and writes. Keeping the two separate
 * means the migration onto the API did not have to touch a single line of SQL
 * or reserialise a single existing row: this record maps between the stored
 * shape and the exposed one at exactly one boundary.
 *
 * Keyed by internal id rather than Minecraft uuid so the restriction follows
 * the person across every account linked to them. Restricting the Minecraft
 * uuid instead would let anyone evade by switching between their Java and
 * Bedrock accounts, which on a network that deliberately accepts both is not
 * a theoretical gap.
 *
 * @param internalId  identity the restriction applies to
 * @param reason      staff supplied reason, shown to the person
 * @param issuedBy    internal id of the issuing staff member, null for console
 * @param issuedAt    epoch millis when the restriction was created
 * @param expiresAt   epoch millis when it lapses, or 0 for permanent
 * @param active      false once lifted, retained for audit rather than deleted
 */
public record VoiceBan(
        UUID internalId,
        String reason,
        UUID issuedBy,
        long issuedAt,
        long expiresAt,
        boolean active
) {
    public VoiceBan {
        if (internalId == null) {
            throw new IllegalArgumentException("internalId cannot be null");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason cannot be blank");
        }
        if (issuedAt <= 0L) {
            throw new IllegalArgumentException("issuedAt must be a positive timestamp");
        }
        if (expiresAt < 0L) {
            throw new IllegalArgumentException("expiresAt cannot be negative");
        }
        if (expiresAt > 0L && expiresAt <= issuedAt) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }

    public boolean isPermanent() {
        return expiresAt == 0L;
    }

    public boolean hasExpired(long now) {
        return !isPermanent() && now >= expiresAt;
    }

    /**
     * Whether this restriction currently prevents speaking. Expiry is evaluated
     * against the clock rather than relying on a scheduled sweep, so a ban
     * always lapses on time even if the sweep is late or the server was offline.
     */
    public boolean isEnforceable(long now) {
        return active && !hasExpired(now);
    }

    public long remainingMillis(long now) {
        if (isPermanent()) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, expiresAt - now);
    }

    public VoiceBan lifted() {
        return new VoiceBan(internalId, reason, issuedBy, issuedAt, expiresAt, false);
    }

    /**
     * The same restriction in the API's vocabulary.
     *
     * The sentinel zero for permanent becomes an empty optional, and a null
     * issuer becomes an empty optional, so that a consumer of the API never
     * has to know the storage encoding to read a restriction correctly.
     */
    public VoiceRestriction toApi() {
        return new VoiceRestriction(
                internalId,
                reason,
                Optional.ofNullable(issuedBy),
                Instant.ofEpochMilli(issuedAt),
                isPermanent() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(expiresAt)),
                active);
    }
}
