package net.codeverse.voice.moderation;

import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.model.VoiceState;
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
        assertEquals(VoiceState.ALLOWED,
                VoiceBanService.decide(strict(), "PREMIUM", true, true, true, false));
        assertEquals(VoiceState.ALLOWED,
                VoiceBanService.decide(strict(), "BEDROCK", true, true, true, false));
        assertEquals(VoiceState.ALLOWED,
                VoiceBanService.decide(strict(), "DISCORD_LINKED", true, true, true, false));
    }

    @Test
    void crackedAccountsAreRefused() {
        assertEquals(VoiceState.UNTRUSTED,
                VoiceBanService.decide(strict(), "CRACKED", true, true, true, false));
    }

    @Test
    void tierIsCheckedBeforePermissionSoAMisconfiguredGroupCannotGrantVoice() {
        // A cracked account that has somehow been granted the speak permission
        // is still refused, and the reported reason is the tier rather than the
        // permission, so staff can see the group is wrong.
        assertEquals(VoiceState.UNTRUSTED,
                VoiceBanService.decide(strict(), "CRACKED", true, true, true, false));
    }

    @Test
    void unresolvedIdentityIsRefusedBeforeAnythingElseIsConsidered() {
        // Permission held, no ban, but the identity is unknown. Refused.
        assertEquals(VoiceState.UNKNOWN_IDENTITY,
                VoiceBanService.decide(strict(), null, false, true, true, false));
    }

    @Test
    void unknownIdentityIsToleratedWhenLinkageIsUnavailable() {
        // With no accounts table present, every account is unknown by
        // definition, so refusing them all would take voice away from the
        // entire server rather than degrading gracefully.
        assertEquals(VoiceState.ALLOWED,
                VoiceBanService.decide(strict(), null, false, false, true, false));
    }

    @Test
    void missingPermissionIsRefused() {
        assertEquals(VoiceState.NO_PERMISSION,
                VoiceBanService.decide(strict(), "PREMIUM", true, true, false, false));
    }

    @Test
    void activeRestrictionIsRefused() {
        assertEquals(VoiceState.BANNED,
                VoiceBanService.decide(strict(), "PREMIUM", true, true, true, true));
    }

    @Test
    void tierComparisonIgnoresCase() {
        assertEquals(VoiceState.ALLOWED,
                VoiceBanService.decide(strict(), "premium", true, true, true, false));
        assertEquals(VoiceState.ALLOWED,
                VoiceBanService.decide(strict(), "Bedrock", true, true, true, false));
    }

    @Test
    void relaxedModeSkipsTierAndIdentityChecks() {
        assertEquals(VoiceState.ALLOWED,
                VoiceBanService.decide(relaxed(), "CRACKED", false, true, true, false));
        // Restrictions and permissions still apply.
        assertEquals(VoiceState.BANNED,
                VoiceBanService.decide(relaxed(), "CRACKED", false, true, true, true));
        assertEquals(VoiceState.NO_PERMISSION,
                VoiceBanService.decide(relaxed(), "CRACKED", false, true, false, false));
    }

    @Test
    void anUnrecognisedTierIsTreatedAsUntrusted() {
        // A tier this plugin has never heard of, perhaps added by a newer
        // authentication release, is refused rather than assumed safe.
        assertEquals(VoiceState.UNTRUSTED,
                VoiceBanService.decide(strict(), "SOME_FUTURE_TIER", true, true, true, false));
    }

    @Test
    void everyDenialIsDistinguishableSoPlayersAreToldTheRightThing() {
        VoiceState untrusted = VoiceBanService.decide(strict(), "CRACKED", true, true, true, false);
        VoiceState noPermission = VoiceBanService.decide(strict(), "PREMIUM", true, true, false, false);
        VoiceState banned = VoiceBanService.decide(strict(), "PREMIUM", true, true, true, true);
        VoiceState unknown = VoiceBanService.decide(strict(), null, false, true, true, false);

        assertEquals(4, java.util.Set.of(untrusted, noPermission, banned, unknown).size());
    }
}
