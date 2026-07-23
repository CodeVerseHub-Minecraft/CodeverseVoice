package net.codeverse.voice.paper.command;

import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.lang.LangManager;
import net.codeverse.voice.model.VoiceBan;
import net.codeverse.voice.moderation.VoiceBanService;
import net.codeverse.voice.paper.gui.HistoryMenu;
import net.codeverse.voice.paper.gui.PlayerMenu;
import net.codeverse.voice.paper.gui.PresetMenu;
import net.codeverse.voice.paper.hook.VoiceHooks;
import net.codeverse.voice.paper.notify.NotificationService;
import net.codeverse.voice.storage.IdentityLookup;
import net.codeverse.voice.sync.VoiceSync;
import net.codeverse.voice.util.DurationParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Staff facing commands.
 *
 * Everything touching storage runs off the main thread, because a stalled
 * database freezes the server tick and a moderation command issued during an
 * incident is exactly when that can least be afforded.
 *
 * Actions that need the audio stream refuse politely when the server does not
 * host voice, rather than failing obscurely, since the same plugin runs on
 * lobbies that only display status.
 */
public final class VoiceCommand implements CommandExecutor, TabCompleter, PlayerMenu.Actions {

    private static final List<String> ACTIONS =
            List.of("gui", "ban", "unban", "check", "history", "monitor", "capture", "presets", "status", "help");

    private final Plugin plugin;
    private final PluginConfig config;
    private final VoiceBanService bans;
    private final VoiceHooks hooks;
    private final IdentityLookup identities;
    private final VoiceSync sync;
    private final NotificationService notifications;
    private final LangManager lang;
    private final Logger logger;

    public VoiceCommand(Plugin plugin,
                        PluginConfig config,
                        VoiceBanService bans,
                        VoiceHooks hooks,
                        IdentityLookup identities,
                        VoiceSync sync,
                        NotificationService notifications,
                        LangManager lang,
                        Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.bans = bans;
        this.hooks = hooks;
        this.identities = identities;
        this.sync = sync;
        this.notifications = notifications;
        this.lang = lang;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (action) {
            case "gui", "menu" -> openMenu(sender, rest);
            case "ban", "mute" -> async(() -> handleBan(sender, rest));
            case "unban", "unmute" -> async(() -> handleUnban(sender, rest));
            case "check", "info" -> async(() -> handleCheck(sender, rest));
            case "history" -> async(() -> handleHistory(sender, rest));
            case "presets" -> openPresetMenu(sender, rest);
            case "monitor", "listen" -> handleMonitor(sender, rest);
            case "capture", "record" -> async(() -> handleCapture(sender, rest));
            case "status" -> handleStatus(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(lang.get("command.usage", localeOf(sender)));
        if (!hooks.isPresent()) {
            sender.sendMessage(lang.get("command.status-only-notice", localeOf(sender)));
        }
    }

    // Menu entry points

    private void openMenu(CommandSender sender, String[] args) {
        if (!(sender instanceof Player staff)) {
            sender.sendMessage(lang.get("command.players-only", localeOf(sender)));
            return;
        }
        if (!config.gui.enabled) {
            staff.sendMessage(lang.get("gui.disabled", staff.locale()));
            return;
        }
        if (!staff.hasPermission("codeverse.voice.check")) {
            staff.sendMessage(lang.get("command.no-permission", staff.locale()));
            return;
        }
        if (args.length < 1) {
            staff.sendMessage(lang.get("command.gui.usage", staff.locale()));
            return;
        }
        Optional<OfflinePlayer> target = resolveTarget(args[0]);
        if (target.isEmpty()) {
            staff.sendMessage(lang.get("command.unknown-player", staff.locale(), "name", args[0]));
            return;
        }
        OfflinePlayer resolved = target.get();

        async(() -> {
            IdentityLookup.Resolved identity = identities.resolve(resolved.getUniqueId());
            Optional<VoiceBan> ban = bans.activeBan(identity.internalId());
            Bukkit.getScheduler().runTask(plugin, () -> new PlayerMenu(config, lang, this,
                    resolved.getUniqueId(), displayName(resolved), ban, identity.tier(), staff.locale())
                    .open(staff));
        });
    }

    private void openPresetMenu(CommandSender sender, String[] args) {
        if (!(sender instanceof Player staff)) {
            sender.sendMessage(lang.get("command.players-only", localeOf(sender)));
            return;
        }
        if (!config.presets.enabled) {
            staff.sendMessage(lang.get("gui.presets-disabled", staff.locale()));
            return;
        }
        if (args.length < 1) {
            staff.sendMessage(lang.get("command.presets.usage", staff.locale()));
            return;
        }
        Optional<OfflinePlayer> target = resolveTarget(args[0]);
        if (target.isEmpty()) {
            staff.sendMessage(lang.get("command.unknown-player", staff.locale(), "name", args[0]));
            return;
        }
        openPresets(staff, target.get().getUniqueId(), displayName(target.get()));
    }

    // PlayerMenu.Actions

    @Override
    public void ban(Player staff, UUID targetMinecraftId, String targetName, String duration, String reason) {
        if (!staff.hasPermission("codeverse.voice.ban")) {
            staff.sendMessage(lang.get("command.no-permission", staff.locale()));
            return;
        }
        long millis = DurationParser.isPermanent(duration)
                ? 0L
                : DurationParser.parse(duration).orElse(-1L);
        if (millis < 0L) {
            staff.sendMessage(lang.get("command.bad-duration", staff.locale(), "input", duration));
            return;
        }
        async(() -> applyBan(staff, targetMinecraftId, targetName, millis, reason));
    }

    @Override
    public void unban(Player staff, UUID targetMinecraftId, String targetName) {
        if (!staff.hasPermission("codeverse.voice.ban")) {
            staff.sendMessage(lang.get("command.no-permission", staff.locale()));
            return;
        }
        async(() -> applyUnban(staff, targetMinecraftId, targetName));
    }

    @Override
    public void monitor(Player staff, UUID targetMinecraftId, String targetName) {
        startMonitoring(staff, targetMinecraftId, targetName);
    }

    @Override
    public void capture(Player staff, UUID targetMinecraftId, String targetName) {
        async(() -> performCapture(staff, targetMinecraftId, targetName, null));
    }

    @Override
    public void openHistory(Player staff, UUID targetMinecraftId, String targetName) {
        async(() -> {
            UUID internalId = identities.resolve(targetMinecraftId).internalId();
            try {
                List<VoiceBan> history = bans.history(internalId, config.gui.historyPageSize);
                Bukkit.getScheduler().runTask(plugin, () ->
                        new HistoryMenu(config, lang, targetName, history, staff.locale()).open(staff));
            } catch (Exception failure) {
                logger.error("Failed to read voice restriction history", failure);
                staff.sendMessage(lang.get("command.storage-error", staff.locale()));
            }
        });
    }

    @Override
    public void openPresets(Player staff, UUID targetMinecraftId, String targetName) {
        Bukkit.getScheduler().runTask(plugin, () ->
                new PresetMenu(config, lang, this, targetMinecraftId, targetName, staff.locale()).open(staff));
    }

    // Command handlers

    private void handleBan(CommandSender sender, String[] args) {
        if (!sender.hasPermission("codeverse.voice.ban")) {
            sender.sendMessage(lang.get("command.no-permission", localeOf(sender)));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(lang.get("command.ban.usage", localeOf(sender)));
            return;
        }
        Optional<OfflinePlayer> target = resolveTarget(args[0]);
        if (target.isEmpty()) {
            sender.sendMessage(lang.get("command.unknown-player", localeOf(sender), "name", args[0]));
            return;
        }
        String durationArgument = args[1];
        long duration;
        if (DurationParser.isPermanent(durationArgument)) {
            duration = 0L;
        } else {
            Optional<Long> parsed = DurationParser.parse(durationArgument);
            if (parsed.isEmpty()) {
                sender.sendMessage(lang.get("command.bad-duration", localeOf(sender), "input", durationArgument));
                return;
            }
            duration = parsed.get();
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        applyBan(sender, target.get().getUniqueId(), displayName(target.get()), duration, reason);
    }

    private void applyBan(CommandSender sender, UUID targetMinecraftId, String targetName,
                          long duration, String reason) {
        UUID targetInternal = identities.resolve(targetMinecraftId).internalId();
        UUID issuerInternal = internalIdOf(sender);
        try {
            VoiceBan ban = bans.ban(targetInternal, reason, issuerInternal, duration);
            sync.publishInvalidate(targetInternal);

            String durationLabel = ban.isPermanent()
                    ? lang.raw("duration.permanent", localeOf(sender))
                    : DurationParser.format(duration);

            sender.sendMessage(lang.get("command.ban.success", localeOf(sender),
                    "name", targetName, "duration", durationLabel, "reason", reason));

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(targetMinecraftId);
                notifications.notifyBanned(online, durationLabel, reason);
                notifications.announceBan(senderName(sender), targetName, durationLabel, reason,
                        sender instanceof Player player ? player.getUniqueId() : null);
            });
        } catch (Exception failure) {
            logger.error("Failed to apply voice restriction", failure);
            sender.sendMessage(lang.get("command.storage-error", localeOf(sender)));
        }
    }

    private void handleUnban(CommandSender sender, String[] args) {
        if (!sender.hasPermission("codeverse.voice.ban")) {
            sender.sendMessage(lang.get("command.no-permission", localeOf(sender)));
            return;
        }
        if (args.length < 1) {
            sender.sendMessage(lang.get("command.unban.usage", localeOf(sender)));
            return;
        }
        Optional<OfflinePlayer> target = resolveTarget(args[0]);
        if (target.isEmpty()) {
            sender.sendMessage(lang.get("command.unknown-player", localeOf(sender), "name", args[0]));
            return;
        }
        applyUnban(sender, target.get().getUniqueId(), displayName(target.get()));
    }

    private void applyUnban(CommandSender sender, UUID targetMinecraftId, String targetName) {
        UUID targetInternal = identities.resolve(targetMinecraftId).internalId();
        try {
            boolean lifted = bans.unban(targetInternal, internalIdOf(sender));
            sync.publishInvalidate(targetInternal);

            sender.sendMessage(lifted
                    ? lang.get("command.unban.success", localeOf(sender), "name", targetName)
                    : lang.get("command.unban.not-banned", localeOf(sender), "name", targetName));

            if (lifted) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    notifications.notifyUnbanned(Bukkit.getPlayer(targetMinecraftId));
                    notifications.announceUnban(senderName(sender), targetName,
                            sender instanceof Player player ? player.getUniqueId() : null);
                });
            }
        } catch (Exception failure) {
            logger.error("Failed to lift voice restriction", failure);
            sender.sendMessage(lang.get("command.storage-error", localeOf(sender)));
        }
    }

    private void handleCheck(CommandSender sender, String[] args) {
        if (!sender.hasPermission("codeverse.voice.check")) {
            sender.sendMessage(lang.get("command.no-permission", localeOf(sender)));
            return;
        }
        if (args.length < 1) {
            sender.sendMessage(lang.get("command.check.usage", localeOf(sender)));
            return;
        }
        Optional<OfflinePlayer> target = resolveTarget(args[0]);
        if (target.isEmpty()) {
            sender.sendMessage(lang.get("command.unknown-player", localeOf(sender), "name", args[0]));
            return;
        }
        IdentityLookup.Resolved resolved = identities.resolve(target.get().getUniqueId());
        Optional<VoiceBan> ban = bans.activeBan(resolved.internalId());

        if (ban.isEmpty()) {
            sender.sendMessage(lang.get("command.check.clear", localeOf(sender),
                    "name", displayName(target.get()),
                    "tier", resolved.tier() == null ? "unknown" : resolved.tier()));
            return;
        }
        VoiceBan active = ban.get();
        sender.sendMessage(lang.get("command.check.banned", localeOf(sender),
                "name", displayName(target.get()),
                "reason", active.reason(),
                "remaining", active.isPermanent()
                        ? lang.raw("duration.permanent", localeOf(sender))
                        : DurationParser.format(active.remainingMillis(System.currentTimeMillis()))));
    }

    private void handleHistory(CommandSender sender, String[] args) {
        if (!sender.hasPermission("codeverse.voice.check")) {
            sender.sendMessage(lang.get("command.no-permission", localeOf(sender)));
            return;
        }
        if (args.length < 1) {
            sender.sendMessage(lang.get("command.history.usage", localeOf(sender)));
            return;
        }
        Optional<OfflinePlayer> target = resolveTarget(args[0]);
        if (target.isEmpty()) {
            sender.sendMessage(lang.get("command.unknown-player", localeOf(sender), "name", args[0]));
            return;
        }
        UUID internalId = identities.resolve(target.get().getUniqueId()).internalId();
        try {
            List<VoiceBan> history = bans.history(internalId, 10);
            if (history.isEmpty()) {
                sender.sendMessage(lang.get("command.history.empty", localeOf(sender),
                        "name", displayName(target.get())));
                return;
            }
            sender.sendMessage(lang.get("command.history.header", localeOf(sender),
                    "name", displayName(target.get()), "count", String.valueOf(history.size())));
            for (VoiceBan entry : history) {
                sender.sendMessage(lang.get("command.history.entry", localeOf(sender),
                        "reason", entry.reason(),
                        "state", entry.isEnforceable(System.currentTimeMillis())
                                ? lang.raw("state.active", localeOf(sender))
                                : lang.raw("state.inactive", localeOf(sender))));
            }
        } catch (Exception failure) {
            logger.error("Failed to read voice restriction history", failure);
            sender.sendMessage(lang.get("command.storage-error", localeOf(sender)));
        }
    }

    private void handleMonitor(CommandSender sender, String[] args) {
        if (!(sender instanceof Player staff)) {
            sender.sendMessage(lang.get("command.players-only", localeOf(sender)));
            return;
        }
        if (!requireVoiceChat(staff)) {
            return;
        }
        if (!staff.hasPermission(config.monitoring.permission)) {
            staff.sendMessage(lang.get("command.no-permission", staff.locale()));
            return;
        }
        if (!hooks.monitoringEnabled()) {
            staff.sendMessage(lang.get("monitor.disabled", staff.locale()));
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("stop")) {
            staff.sendMessage(hooks.stopMonitor(staff.getUniqueId(), "stopped by staff member")
                    ? lang.get("monitor.stopped", staff.locale())
                    : lang.get("monitor.not-monitoring", staff.locale()));
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("extend")) {
            staff.sendMessage(switch (hooks.extendMonitor(staff.getUniqueId())) {
                case STARTED -> lang.get("monitor.extended", staff.locale(),
                        "seconds", String.valueOf(config.monitoring.automaticStopSeconds));
                case EXTENSION_LIMIT -> lang.get("monitor.extension-limit", staff.locale(),
                        "limit", String.valueOf(config.monitoring.maximumExtensions));
                default -> lang.get("monitor.not-monitoring", staff.locale());
            });
            return;
        }
        if (args.length < 1) {
            staff.sendMessage(lang.get("command.monitor.usage", staff.locale()));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            staff.sendMessage(lang.get("command.unknown-player", staff.locale(), "name", args[0]));
            return;
        }
        startMonitoring(staff, target.getUniqueId(), target.getName());
    }

    private void startMonitoring(Player staff, UUID targetMinecraftId, String targetName) {
        if (!requireVoiceChat(staff)) {
            return;
        }
        if (!staff.hasPermission(config.monitoring.permission)) {
            staff.sendMessage(lang.get("command.no-permission", staff.locale()));
            return;
        }
        if (targetMinecraftId.equals(staff.getUniqueId())) {
            staff.sendMessage(lang.get("monitor.cannot-self", staff.locale()));
            return;
        }
        UUID staffInternal = identities.resolve(staff.getUniqueId()).internalId();
        UUID targetInternal = identities.resolve(targetMinecraftId).internalId();

        VoiceHooks.MonitorOutcome outcome = hooks.startMonitor(
                staff.getUniqueId(), staffInternal, targetMinecraftId, targetInternal, targetName);

        if (outcome == VoiceHooks.MonitorOutcome.ALREADY_ACTIVE) {
            staff.sendMessage(lang.get("monitor.already-active", staff.locale()));
            return;
        }
        if (outcome == VoiceHooks.MonitorOutcome.UNAVAILABLE) {
            staff.sendMessage(lang.get("command.requires-voice-server", staff.locale()));
            return;
        }
        staff.sendMessage(lang.get("monitor.started", staff.locale(),
                "name", targetName, "seconds", String.valueOf(config.monitoring.automaticStopSeconds)));

        if (outcome == VoiceHooks.MonitorOutcome.STARTED_WITHOUT_AUDIT) {
            staff.sendMessage(lang.get("monitor.audit-failed", staff.locale()));
        }
        if (config.monitoring.announceToStaff) {
            notifications.toStaff("monitor.staff-announcement", staff.getUniqueId(),
                    "staff", staff.getName(), "target", targetName);
        }
    }

    private void handleCapture(CommandSender sender, String[] args) {
        if (!sender.hasPermission("codeverse.voice.capture")) {
            sender.sendMessage(lang.get("command.no-permission", localeOf(sender)));
            return;
        }
        if (sender instanceof Player staff && !requireVoiceChat(staff)) {
            return;
        }
        if (!hooks.recordingEnabled()) {
            sender.sendMessage(lang.get("capture.disabled", localeOf(sender)));
            return;
        }
        if (args.length < 1) {
            sender.sendMessage(lang.get("command.capture.usage", localeOf(sender)));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(lang.get("command.unknown-player", localeOf(sender), "name", args[0]));
            return;
        }
        String note = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : null;
        performCapture(sender, target.getUniqueId(), target.getName(), note);
    }

    private void performCapture(CommandSender sender, UUID targetMinecraftId, String targetName, String note) {
        if (!hooks.recordingEnabled()) {
            sender.sendMessage(lang.get("capture.disabled", localeOf(sender)));
            return;
        }
        UUID targetInternal = identities.resolve(targetMinecraftId).internalId();
        Optional<String> file = hooks.capture(targetMinecraftId, targetInternal, internalIdOf(sender), note);
        if (file.isEmpty()) {
            sender.sendMessage(lang.get("capture.nothing-buffered", localeOf(sender), "name", targetName));
            return;
        }
        sender.sendMessage(lang.get("capture.success", localeOf(sender),
                "name", targetName, "file", file.get(),
                "days", String.valueOf(config.recording.retentionDays)));

        long used = hooks.recordingMegabytes();
        long budget = config.recording.maximumDirectoryMegabytes;
        if (budget > 0 && used * 100L / budget >= config.recording.warnAtPercentOfBudget) {
            sender.sendMessage(lang.get("capture.over-budget", localeOf(sender),
                    "used", String.valueOf(used), "budget", String.valueOf(budget)));
        }
    }

    private void handleStatus(CommandSender sender) {
        if (!sender.hasPermission("codeverse.voice.check")) {
            sender.sendMessage(lang.get("command.no-permission", localeOf(sender)));
            return;
        }
        sender.sendMessage(lang.get("command.status", localeOf(sender),
                "mode", hooks.isPresent()
                        ? lang.raw("status.full", localeOf(sender))
                        : lang.raw("status.status-only", localeOf(sender)),
                "identities", identities.isUsingAuthIdentities()
                        ? lang.raw("status.linked", localeOf(sender))
                        : lang.raw("status.fallback", localeOf(sender)),
                "sync", sync.isHealthy()
                        ? lang.raw("status.connected", localeOf(sender))
                        : lang.raw("status.offline", localeOf(sender)),
                "monitors", String.valueOf(hooks.activeMonitorCount()),
                "recordings", hooks.recordingEnabled()
                        ? String.valueOf(hooks.recordingMegabytes())
                        : lang.raw("status.disabled", localeOf(sender))));
    }

    /** Refuses audio dependent actions on servers that do not host voice. */
    private boolean requireVoiceChat(Player staff) {
        if (hooks.isPresent()) {
            return true;
        }
        staff.sendMessage(lang.get("command.requires-voice-server", staff.locale()));
        return false;
    }

    private Optional<OfflinePlayer> resolveTarget(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online);
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? Optional.of(offline) : Optional.empty();
    }

    private UUID internalIdOf(CommandSender sender) {
        return sender instanceof Player player ? identities.resolve(player.getUniqueId()).internalId() : null;
    }

    private static String senderName(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : "Console";
    }

    private static String displayName(OfflinePlayer player) {
        String name = player.getName();
        return name == null ? player.getUniqueId().toString() : name;
    }

    private static Locale localeOf(CommandSender sender) {
        return sender instanceof Player player ? player.locale() : null;
    }

    private void async(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(ACTIONS, args[0]);
        }
        if (args.length == 2) {
            String action = args[0].toLowerCase(Locale.ROOT);
            if (action.equals("monitor") || action.equals("listen")) {
                List<String> options = new ArrayList<>(List.of("stop", "extend"));
                options.addAll(onlineNames());
                return filter(options, args[1]);
            }
            if (!action.equals("status") && !action.equals("help")) {
                return filter(onlineNames(), args[1]);
            }
        }
        if (args.length == 3 && List.of("ban", "mute").contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> durations = new ArrayList<>(config.gui.quickDurations);
            if (!durations.contains("perm")) {
                durations.add("perm");
            }
            return filter(durations, args[2]);
        }
        return List.of();
    }

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lowered)) {
                out.add(option);
            }
        }
        return out;
    }
}
