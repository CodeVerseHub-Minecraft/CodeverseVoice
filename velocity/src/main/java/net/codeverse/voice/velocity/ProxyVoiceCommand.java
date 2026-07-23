package net.codeverse.voice.velocity;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.codeverse.voice.lang.LangManager;
import net.codeverse.voice.model.VoiceBan;
import net.codeverse.voice.moderation.VoiceBanService;
import net.codeverse.voice.storage.IdentityLookup;
import net.codeverse.voice.sync.VoiceSync;
import net.codeverse.voice.util.DurationParser;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Network wide moderation commands, usable from any server.
 *
 * Only offers actions that make sense away from the audio: restricting,
 * lifting, and looking someone up. Monitoring and capture stay on the voice
 * server, because both need the audio stream that only exists there.
 */
public final class ProxyVoiceCommand {

    private final VoiceBanService bans;
    private final IdentityLookup identities;
    private final VoiceSync sync;
    private final LangManager lang;
    private final ProxyServer proxy;
    private final Logger logger;

    public ProxyVoiceCommand(VoiceBanService bans,
                             IdentityLookup identities,
                             VoiceSync sync,
                             LangManager lang,
                             ProxyServer proxy,
                             Logger logger) {
        this.bans = bans;
        this.identities = identities;
        this.sync = sync;
        this.lang = lang;
        this.proxy = proxy;
        this.logger = logger;
    }

    public BrigadierCommand build() {
        LiteralArgumentBuilder<CommandSource> root = BrigadierCommand.literalArgumentBuilder("voice")
                .requires(source -> source.hasPermission("codeverse.voice.check"))
                .executes(context -> {
                    context.getSource().sendMessage(lang.get("command.proxy.usage", localeOf(context.getSource())));
                    return 1;
                })
                .then(BrigadierCommand.literalArgumentBuilder("ban")
                        .requires(source -> source.hasPermission("codeverse.voice.ban"))
                        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .then(BrigadierCommand.requiredArgumentBuilder("duration", StringArgumentType.word())
                                        .then(BrigadierCommand.requiredArgumentBuilder(
                                                        "reason", StringArgumentType.greedyString())
                                                .executes(context -> {
                                                    handleBan(context.getSource(),
                                                            context.getArgument("player", String.class),
                                                            context.getArgument("duration", String.class),
                                                            context.getArgument("reason", String.class));
                                                    return 1;
                                                })))))
                .then(BrigadierCommand.literalArgumentBuilder("unban")
                        .requires(source -> source.hasPermission("codeverse.voice.ban"))
                        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .executes(context -> {
                                    handleUnban(context.getSource(), context.getArgument("player", String.class));
                                    return 1;
                                })))
                .then(BrigadierCommand.literalArgumentBuilder("check")
                        .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                                .executes(context -> {
                                    handleCheck(context.getSource(), context.getArgument("player", String.class));
                                    return 1;
                                })));
        return new BrigadierCommand(root);
    }

    private void handleBan(CommandSource source, String name, String durationArgument, String reason) {
        Optional<Player> target = proxy.getPlayer(name);
        if (target.isEmpty()) {
            source.sendMessage(lang.get("command.unknown-player", localeOf(source), "name", name));
            return;
        }
        long duration;
        if (DurationParser.isPermanent(durationArgument)) {
            duration = 0L;
        } else {
            Optional<Long> parsed = DurationParser.parse(durationArgument);
            if (parsed.isEmpty()) {
                source.sendMessage(lang.get("command.bad-duration", localeOf(source), "input", durationArgument));
                return;
            }
            duration = parsed.get();
        }

        UUID internalId = identities.resolve(target.get().getUniqueId()).internalId();
        try {
            VoiceBan ban = bans.ban(internalId, reason, internalIdOf(source), duration);
            sync.publishInvalidate(internalId);
            source.sendMessage(lang.get("command.ban.success", localeOf(source),
                    "name", target.get().getUsername(),
                    "duration", ban.isPermanent()
                            ? lang.raw("duration.permanent", localeOf(source))
                            : DurationParser.format(duration),
                    "reason", reason));
        } catch (Exception failure) {
            logger.error("Failed to apply voice restriction from the proxy", failure);
            source.sendMessage(lang.get("command.storage-error", localeOf(source)));
        }
    }

    private void handleUnban(CommandSource source, String name) {
        Optional<Player> target = proxy.getPlayer(name);
        if (target.isEmpty()) {
            source.sendMessage(lang.get("command.unknown-player", localeOf(source), "name", name));
            return;
        }
        UUID internalId = identities.resolve(target.get().getUniqueId()).internalId();
        try {
            boolean lifted = bans.unban(internalId, internalIdOf(source));
            sync.publishInvalidate(internalId);
            source.sendMessage(lifted
                    ? lang.get("command.unban.success", localeOf(source), "name", target.get().getUsername())
                    : lang.get("command.unban.not-banned", localeOf(source), "name", target.get().getUsername()));
        } catch (Exception failure) {
            logger.error("Failed to lift voice restriction from the proxy", failure);
            source.sendMessage(lang.get("command.storage-error", localeOf(source)));
        }
    }

    private void handleCheck(CommandSource source, String name) {
        Optional<Player> target = proxy.getPlayer(name);
        if (target.isEmpty()) {
            source.sendMessage(lang.get("command.unknown-player", localeOf(source), "name", name));
            return;
        }
        IdentityLookup.Resolved resolved = identities.resolve(target.get().getUniqueId());
        Optional<VoiceBan> ban = bans.activeBan(resolved.internalId());
        if (ban.isEmpty()) {
            source.sendMessage(lang.get("command.check.clear", localeOf(source),
                    "name", target.get().getUsername(),
                    "tier", resolved.tier() == null ? "unknown" : resolved.tier()));
            return;
        }
        VoiceBan active = ban.get();
        source.sendMessage(lang.get("command.check.banned", localeOf(source),
                "name", target.get().getUsername(),
                "reason", active.reason(),
                "remaining", active.isPermanent()
                        ? lang.raw("duration.permanent", localeOf(source))
                        : DurationParser.format(active.remainingMillis(System.currentTimeMillis()))));
    }

    private UUID internalIdOf(CommandSource source) {
        return source instanceof Player player ? identities.resolve(player.getUniqueId()).internalId() : null;
    }

    private static Locale localeOf(CommandSource source) {
        return source instanceof Player player ? player.getEffectiveLocale() : null;
    }
}
