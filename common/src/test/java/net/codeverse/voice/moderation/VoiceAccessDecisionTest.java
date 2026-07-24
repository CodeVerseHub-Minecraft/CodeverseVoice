package net.codeverse.voice.moderation;

import net.codeverse.api.identity.TrustTier;
import net.codeverse.api.voice.VoiceAccess;
import net.codeverse.voice.config.PluginConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The access decision is the plugin's security surface, so its behaviour is
 * pinned rather than assumed.
 *
 * Two properties are load bearing. An identity that cannot be resolved is
 * refused rather than waved through, matching the fail closed stance the
 * network's authentication layer takes. And a cracked tier is refused before
 * permissions are consulted at all, so a group misconfigured in LuckPerms
 * cannot hand voice to an unverified account.
 */
class VoiceAccessDecisionTest {

    private static PluginConfig.Access strict() {
        PluginConfig.Access access = new PluginConfig.Access();
        access.requireVerifiedOrigin = true;
        access.trustedTiers = List.of("PREMIUM", "BEDROCK", "DISCORD_LINKED");
        return access;
    }

    private static PluginConfig.Access relaxed() {
        PluginConfig.Access access = new PluginConfig.Access();
        access.requireVerifiedOrigin = false;
        access.trustedTiers = List.of("PREMIUM");
        return access;
    }

    @Test
    void verifiedAccountWithPermissionAndNoBanMaySpeak() {
        assertEquals(VoiceAccess.ALLOWED,
                VoiceBanService.decide(strict(), TrustTier.PREMIUM, true, true, true, false));
        assertEquals(VoiceAccess.ALLOWED,
                VoiceBanService.decide(strict(), TrustTier.BEDROCK, true, true, true, false));
        assertEquals(VoiceAccess.ALLOWED,
                VoiceBanService.decide(strict(), TrustTier.DISCORD_LINKED, true, true, true, false));
    }

    @Test
    void crackedAccountsAreRefused() {
        assertEquals(VoiceAccess.UNTRUSTED,
                VoiceBanService.decide(strict(), TrustTier.CRACKED, true, true, true, false));
    }

    @Test
    void tierIsCheckedBeforePermissionSoAMisconfiguredGroupCannotGrantVoice() {
        // A cracked account that has somehow been granted the speak permission
        // is still refused, and the reported reason is the tier rather than the
        // permission, so staff can see the group is wrong.
        assertEquals(VoiceAccess.UNTRUSTED,
                VoiceBanService.decide(strict(), TrustTier.CRACKED, true, true, true, false));
    }

    @Test
    void unresolvedIdentityIsRefusedBeforeAnythingElseIsConsidered() {
        // Permission held, no ban, but the identity is unknown. Refused.
        assertEquals(VoiceAccess.UNKNOWN_IDENTITY,
                VoiceBanService.decide(strict(), null, false, true, true, false));
    }

    @Test
    void unknownIdentityIsToleratedWhenLinkageIsUnavailable() {
        // With no accounts table present, every account is unknown by
        // definition, so refusing them all would take voice away from the
        // entire server rather than degrading gracefully.
        assertEquals(VoiceAccess.ALLOWED,
                VoiceBanService.decide(strict(), null, false, false, true, false));
    }

    @Test
    void missingPermissionIsRefused() {
        assertEquals(VoiceAccess.NO_PERMISSION,
                VoiceBanService.decide(strict(), TrustTier.PREMIUM, true, true, false, false));
    }

    @Test
    void activeRestrictionIsRefused() {
        assertEquals(VoiceAccess.RESTRICTED,
                VoiceBanService.decide(strict(), TrustTier.PREMIUM, true, true, true, true));
    }

    @Test
    void relaxedModeSkipsTierAndIdentityChecks() {
        assertEquals(VoiceAccess.ALLOWED,
                VoiceBanService.decide(relaxed(), TrustTier.CRACKED, false, true, true, false));
        // Restrictions and permissions still apply.
        assertEquals(VoiceAccess.RESTRICTED,
                VoiceBanService.decide(relaxed(), TrustTier.CRACKED, false, true, true, true));
        assertEquals(VoiceAccess.NO_PERMISSION,
                VoiceBanService.decide(relaxed(), TrustTier.CRACKED, false, true, false, false));
    }

    /**
     * A tier this plugin has never heard of, perhaps added by a newer
     * authentication release, is resolved to CRACKED upstream in the identity
     * layer rather than reaching this decision as an unknown string. So the
     * fail closed behaviour is that an unrecognised tier arrives here already
     * degraded to the least trusted, and is refused on that basis.
     */
    @Test
    void anUnrecognisedTierDegradesToCrackedAndIsRefused() {
        assertEquals(java.util.Optional.of(TrustTier.CRACKED),
                java.util.Optional.of(TrustTier.parse("SOME_FUTURE_TIER").orElse(TrustTier.CRACKED)));
        assertEquals(VoiceAccess.UNTRUSTED,
                VoiceBanService.decide(strict(), TrustTier.CRACKED, true, true, true, false));
    }

    @Test
    void everyDenialIsDistinguishableSoPlayersAreToldTheRightThing() {
        VoiceAccess untrusted = VoiceBanService.decide(strict(), TrustTier.CRACKED, true, true, true, false);
        VoiceAccess noPermission = VoiceBanService.decide(strict(), TrustTier.PREMIUM, true, true, false, false);
        VoiceAccess banned = VoiceBanService.decide(strict(), TrustTier.PREMIUM, true, true, true, true);
        VoiceAccess unknown = VoiceBanService.decide(strict(), null, false, true, true, false);

        assertEquals(4, java.util.Set.of(untrusted, noPermission, banned, unknown).size());
    }
}
