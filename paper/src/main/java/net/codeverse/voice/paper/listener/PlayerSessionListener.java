package net.codeverse.voice.paper.listener;

import net.codeverse.voice.model.VoiceBan;
import net.codeverse.voice.moderation.VoiceBanService;
import net.codeverse.voice.paper.hook.VoiceHooks;
import net.codeverse.voice.paper.notify.NotificationService;
import net.codeverse.voice.storage.IdentityLookup;
import net.codeverse.voice.util.DurationParser;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * Session bookkeeping around joins and quits.
 *
 * Warms the restriction cache on join so placeholders have something to show
 * immediately, reminds a restricted player how long is left, and releases
 * everything held for someone once they leave.
 */
public final class PlayerSessionListener implements Listener {

    private final VoiceBanService bans;
    private final IdentityLookup identities;
    private final NotificationService notifications;
    private final VoiceHooks hooks;
    private final org.bukkit.plugin.Plugin plugin;

    public PlayerSessionListener(org.bukkit.plugin.Plugin plugin,
                                 VoiceBanService bans,
                                 IdentityLookup identities,
                                 NotificationService notifications,
                                 VoiceHooks hooks) {
        this.plugin = plugin;
        this.bans = bans;
        this.identities = identities;
        this.notifications = notifications;
        this.hooks = hooks;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID minecraftId = event.getPlayer().getUniqueId();

        // Resolving identity and restriction reads the database, so it happens
        // off the main thread. The join itself is never delayed by it.
        Bukkit.getScheduler().runTaskAsynchronously(
                plugin, () -> {
                    UUID internalId = identities.resolve(minecraftId).internalId();
                    Optional<VoiceBan> ban = bans.activeBan(internalId);
                    if (ban.isEmpty() || !ban.get().isEnforceable(System.currentTimeMillis())) {
                        return;
                    }
                    VoiceBan active = ban.get();
                    String remaining = active.isPermanent()
                            ? "permanent"
                            : DurationParser.format(active.remainingMillis(System.currentTimeMillis()));

                    // Messaging happens back on the main thread.
                    Bukkit.getScheduler().runTask(
                            plugin, () -> {
                                if (event.getPlayer().isOnline()) {
                                    notifications.remindOnJoin(event.getPlayer(), remaining, active.reason());
                                }
                            });
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID minecraftId = event.getPlayer().getUniqueId();
        // Buffered audio belongs to a session, not to an account, so it is
        // dropped rather than left in memory for someone who has left.
        hooks.forgetSession(minecraftId);
    }
}
