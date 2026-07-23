package net.codeverse.voice.paper.notify;

import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.lang.LangManager;
import net.codeverse.voice.paper.util.Sounds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * Delivers every player facing announcement the plugin makes.
 *
 * Centralised so that each message has exactly one switch controlling it. When
 * notification decisions are scattered through the code, turning something off
 * in config reliably misses one path, and the one it misses is usually the one
 * that annoys people.
 */
public final class NotificationService {

    private final PluginConfig config;
    private final LangManager lang;

    public NotificationService(PluginConfig config, LangManager lang) {
        this.config = config;
        this.lang = lang;
    }

    /** Sends the denial notice in whichever style is configured. */
    public void sendDenial(Player player, String messageKey) {
        if (!config.access.notifyOnDenial) {
            return;
        }
        Component message = lang.get(messageKey, player.locale());
        switch (config.access.denialNoticeStyle.toUpperCase(Locale.ROOT)) {
            case "CHAT" -> player.sendMessage(message);
            case "ACTION_BAR" -> player.sendActionBar(message);
            case "TITLE" -> player.showTitle(Title.title(Component.empty(), message,
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(400))));
            default -> {
                // NONE, deliberately silent.
            }
        }
    }

    public void notifyBanned(Player target, String duration, String reason) {
        if (!config.notifications.notifyTargetOnBan || target == null) {
            return;
        }
        target.sendMessage(lang.get("voice.you-were-banned", target.locale(),
                "duration", duration, "reason", reason));
        playSound(target, config.notifications.targetSound);
    }

    public void notifyUnbanned(Player target) {
        if (!config.notifications.notifyTargetOnUnban || target == null) {
            return;
        }
        target.sendMessage(lang.get("voice.you-were-unbanned", target.locale()));
        playSound(target, config.notifications.staffSound);
    }

    public void notifyExpired(Player target) {
        if (!config.notifications.notifyTargetOnExpiry || target == null) {
            return;
        }
        target.sendMessage(lang.get("voice.restriction-expired", target.locale()));
        playSound(target, config.notifications.staffSound);
    }

    /** Reminds someone on join that they are still restricted, and for how long. */
    public void remindOnJoin(Player target, String remaining, String reason) {
        if (!config.notifications.remindOnJoin || target == null) {
            return;
        }
        target.sendMessage(lang.get("voice.join-reminder", target.locale(),
                "remaining", remaining, "reason", reason));
    }

    public void announceBan(String staffName, String targetName, String duration, String reason, UUID exclude) {
        if (config.notifications.notifyStaffOnBan) {
            toStaff("notify.staff.ban", exclude,
                    "staff", staffName, "target", targetName, "duration", duration, "reason", reason);
        }
        if (config.notifications.broadcastBans) {
            toEveryone("notify.broadcast.ban", "target", targetName, "duration", duration, "reason", reason);
        }
    }

    public void announceUnban(String staffName, String targetName, UUID exclude) {
        if (config.notifications.notifyStaffOnUnban) {
            toStaff("notify.staff.unban", exclude, "staff", staffName, "target", targetName);
        }
        if (config.notifications.broadcastUnbans) {
            toEveryone("notify.broadcast.unban", "target", targetName);
        }
    }

    public void toStaff(String key, UUID exclude, String... placeholders) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (exclude != null && online.getUniqueId().equals(exclude)) {
                continue;
            }
            if (!online.hasPermission(config.notifications.staffPermission)) {
                continue;
            }
            online.sendMessage(lang.get(key, online.locale(), placeholders));
            playSound(online, config.notifications.staffSound);
        }
    }

    private void toEveryone(String key, String... placeholders) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(lang.get(key, online.locale(), placeholders));
        }
    }

    private void playSound(Player player, String soundName) {
        if (!config.notifications.playSounds || soundName == null || soundName.isBlank()) {
            return;
        }
        // A mistyped sound must not break the notification it accompanies.
        Sounds.resolve(soundName).ifPresent(sound -> player.playSound(player.getLocation(), sound,
                config.notifications.soundVolume, config.notifications.soundPitch));
    }
}
