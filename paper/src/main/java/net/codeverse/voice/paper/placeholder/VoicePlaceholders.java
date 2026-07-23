package net.codeverse.voice.paper.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.model.VoiceBan;
import net.codeverse.voice.moderation.VoiceBanService;
import net.codeverse.voice.storage.IdentityLookup;
import net.codeverse.voice.util.DurationParser;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Exposes voice status to PlaceholderAPI.
 *
 * Placeholders are resolved on the main thread, often once per tick per player
 * on a scoreboard, so nothing here may touch the database or Redis. Every value
 * comes from the in memory cache maintained by the restriction service, which
 * is kept current by Redis messages from whichever server issued the action.
 *
 * That is what allows the lobby to display a restriction issued on the voice
 * server: the lobby runs this module too, shares the cache, and never has to
 * ask the voice server anything.
 *
 * Available placeholders:
 *   %codeversevoice_muted%      Restricted or Active, styled from config
 *   %codeversevoice_is_muted%   true or false, for conditionals
 *   %codeversevoice_remaining%  1h30m, permanent, or the none label
 *   %codeversevoice_reason%     the restriction reason, or the none label
 *   %codeversevoice_tier%       stored trust tier
 *   %codeversevoice_can_speak%  true or false, including tier and permission
 */
public final class VoicePlaceholders extends PlaceholderExpansion {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final PluginConfig config;
    private final VoiceBanService bans;
    private final IdentityLookup identities;
    private final String version;

    public VoicePlaceholders(PluginConfig config, VoiceBanService bans, IdentityLookup identities, String version) {
        this.config = config;
        this.bans = bans;
        this.identities = identities;
        this.version = version;
    }

    @Override
    public String getIdentifier() {
        return "codeversevoice";
    }

    @Override
    public String getAuthor() {
        return "CodeVerseHub-Minecraft Subteam";
    }

    @Override
    public String getVersion() {
        return version;
    }

    /** Survives a PlaceholderAPI reload without needing the plugin restarted. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (!config.placeholders.enabled || player == null) {
            return "";
        }
        UUID minecraftId = player.getUniqueId();
        IdentityLookup.Resolved resolved = identities.resolveCached(minecraftId);
        if (resolved == null) {
            // Nothing cached yet. Returning the loading label rather than an
            // empty string keeps a scoreboard from flickering blank on join.
            return config.placeholders.loadingLabel;
        }

        Optional<VoiceBan> ban = bans.cachedBan(resolved.internalId());
        long now = System.currentTimeMillis();
        boolean restricted = ban.isPresent() && ban.get().isEnforceable(now);

        return switch (params.toLowerCase(Locale.ROOT)) {
            case "muted", "status" -> render(restricted
                    ? config.placeholders.mutedYes
                    : config.placeholders.mutedNo);
            case "is_muted" -> String.valueOf(restricted);
            case "remaining" -> {
                if (!restricted) {
                    yield config.placeholders.noRestriction;
                }
                yield ban.get().isPermanent()
                        ? config.placeholders.permanentLabel
                        : DurationParser.format(ban.get().remainingMillis(now));
            }
            case "remaining_seconds" -> {
                if (!restricted || ban.get().isPermanent()) {
                    yield "0";
                }
                yield String.valueOf(ban.get().remainingMillis(now) / 1000L);
            }
            case "reason" -> restricted ? ban.get().reason() : config.placeholders.noRestriction;
            case "tier" -> resolved.tier() == null ? config.placeholders.noRestriction : resolved.tier();
            case "can_speak" -> {
                if (!(player instanceof Player online)) {
                    yield String.valueOf(!restricted);
                }
                yield String.valueOf(VoiceBanService.decide(
                        config.access,
                        resolved.tier(),
                        resolved.known(),
                        identities.isUsingAuthIdentities(),
                        online.hasPermission(config.access.speakPermission),
                        restricted).allowed());
            }
            default -> null;
        };
    }

    /** Placeholder output is legacy coloured text, so MiniMessage is rendered down. */
    private static String render(String miniMessage) {
        return LEGACY.serialize(MINI.deserialize(miniMessage));
    }
}
