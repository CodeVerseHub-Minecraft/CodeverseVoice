package net.codeverse.voice.model;

import java.util.UUID;

/**
 * A restriction on a person's ability to speak in voice chat.
 *
 * Keyed by internal id rather than Minecraft uuid so that the restriction
 * follows the person across every account linked to them. Banning the
 * Minecraft uuid instead would let anyone evade by switching between their
 * Java and Bedrock accounts, which on a network that deliberately accepts
 * both is not a theoretical gap.
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
}
