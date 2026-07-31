package net.codeverse.voice.paper;

import net.codeverse.api.CodeverseApiProvider;
import net.codeverse.api.voice.VoiceService;
import net.codeverse.api.identity.IdentityService;
import net.codeverse.jdbc.JdbcIdentityService;
import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.lang.LangManager;
import net.codeverse.voice.moderation.CodeverseVoiceService;
import net.codeverse.voice.moderation.VoiceBanService;
import net.codeverse.voice.paper.command.VoiceCommand;
import net.codeverse.voice.paper.gui.MenuListener;
import net.codeverse.voice.paper.hook.VoiceChatBridge;
import net.codeverse.voice.paper.hook.VoiceHooks;
import net.codeverse.voice.paper.listener.PlayerSessionListener;
import net.codeverse.voice.paper.notify.NotificationService;
import net.codeverse.voice.paper.placeholder.VoicePlaceholders;
import net.codeverse.voice.paper.updatecheck.UpdateCheck;
import net.codeverse.voice.storage.Database;
import net.codeverse.voice.storage.IdentityResolver;
import net.codeverse.voice.storage.VoiceBanRepository;
import net.codeverse.voice.sync.VoiceSync;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Plugin entry point.
 *
 * Runs in one of two modes depending on what is installed alongside it.
 *
 * With Simple Voice Chat present, the full moderation layer is active:
 * enforcement, monitoring, recording and the menus.
 *
 * Without it, the plugin still loads and provides placeholders, commands and
 * notifications. That is not a degraded fallback but the intended arrangement
 * for servers that display voice status without hosting voice, such as a lobby
 * showing a restriction issued on the survival server. Disabling itself there
 * would take the placeholders with it.
 *
 * Startup is otherwise all or nothing. If configuration, storage or messages
 * cannot be loaded, nothing is registered, which leaves voice chat unmoderated
 * rather than running a half initialised moderation layer that silently permits
 * everything.
 */
public final class CodeverseVoicePaper extends JavaPlugin {

    private static final List<String> BUNDLED_LOCALES = List.of("en", "de");
    private static final Logger LOGGER = LoggerFactory.getLogger("CodeverseVoice");

    private PluginConfig config;
    private LangManager lang;
    private Database database;
    private VoiceSync sync;
    private VoiceBanService bans;
    private IdentityResolver identities;
    private JdbcIdentityService jdbcIdentities;
    private CodeverseVoiceService voiceService;
    private java.util.concurrent.ExecutorService identityExecutor;
    private NotificationService notifications;
    private VoiceHooks hooks = VoiceHooks.ABSENT;

    @Override
    public void onEnable() {
        try {
            config = PluginConfig.load(getDataFolder().toPath());
            lang = new LangManager(getDataFolder().toPath(), config.language.defaultLocale,
                    config.language.usePlayerLocale, BUNDLED_LOCALES);

            database = new Database(config.storage);
            database.applySchema();

            VoiceBanRepository repository = new VoiceBanRepository(database);

            identityExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            jdbcIdentities = new JdbcIdentityService(
                    database.dataSource(),
                    identityExecutor,
                    config.identity.accountsTable,
                    java.time.Duration.ofSeconds(Math.max(30, config.identity.cacheSeconds)));

            boolean linkage = config.identity.useAuthPluginIdentities && jdbcIdentities.probe();
            if (config.identity.useAuthPluginIdentities && !linkage) {
                LOGGER.error("The accounts table '{}' could not be read. Voice restrictions will be keyed to "
                                + "individual Minecraft accounts instead of network identities, which means "
                                + "someone can evade a restriction by switching between their linked accounts. "
                                + "Check storage settings and that the authentication plugin has run at least once.",
                        config.identity.accountsTable);
            }
            identities = new IdentityResolver(jdbcIdentities, linkage);

            bans = new VoiceBanService(repository, jdbcIdentities, linkage, config.access);
            notifications = new NotificationService(config, lang);

            // A backend enforces, so the service this contributes reports
            // isEnforcing true. Since 0.4.0 this contributes one service rather
            // than registering the whole API: CodeverseExtension owns that slot
            // and provides identity, and a second registration here would be a
            // second registry that nothing else on the server could read.
            voiceService = new CodeverseVoiceService(
                    bans, jdbcIdentities, config.access, linkage, true, identityExecutor);
            CodeverseApiProvider.registerService(VoiceService.class, voiceService);
            if (CodeverseApiProvider.find().isEmpty()) {
                // Extension is a hard dependency, so it is installed and has
                // enabled before this. An absent registration therefore means
                // it enabled and then failed, which leaves the contributed
                // service published where nothing is reading. Worth saying,
                // and not worth refusing to start over: voice enforcement here
                // does not depend on anyone reading it.
                LOGGER.warn("CodeverseExtension has not registered the network API, so the voice "
                        + "service has been contributed where nothing can currently read it. Voice "
                        + "moderation on this server is unaffected. Check the extension's startup.");
            }

            sync = new VoiceSync(config.redis);
            if (config.redis.enabled && !sync.start(bans::invalidate, bans::invalidateAll)) {
                LOGGER.warn("Redis is enabled but unreachable. Moderation actions taken on other servers will "
                        + "not apply here until the local cache expires, and placeholders may show stale values.");
            }

            registerVoiceChat(repository);
            registerPlaceholders();

            getServer().getPluginManager().registerEvents(new MenuListener(), this);
            getServer().getPluginManager().registerEvents(
                    new PlayerSessionListener(this, bans, identities, notifications, hooks), this);

            registerCommand();
            scheduleMaintenance();

            if (config.updates.checkOnStartup) {
                // Runs off the main thread since it makes a network request.
                // getUpdateFolderFile is the folder Paper swaps a replacement
                // jar in from on the next restart, so the staged jar lands
                // exactly where the platform expects it.
                String version = getPluginMeta().getVersion();
                Path updateFolder = getServer().getUpdateFolderFile().toPath();
                // Repeats rather than running once, so a server that stays up
                // for a fortnight still learns about a release published an
                // hour after it booted.
                long periodTicks = Math.max(1L, config.updates.checkIntervalHours) * 60L * 60L * 20L;
                Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> UpdateCheck.run(
                                version, updateFolder, config.updates.autoApply,
                                config.updates.checkIntervalHours, Runnable::run, LOGGER),
                        20L, periodTicks);
            }

            LOGGER.info("Voice moderation ready in {} mode. Identities {}, recording {}, monitoring {}, locales {}",
                    hooks.isPresent() ? "full" : "status only",
                    identities.isUsingAuthIdentities() ? "linked to network accounts" : "per Minecraft account",
                    hooks.recordingEnabled() ? config.recording.bufferSeconds + "s buffer" : "disabled",
                    hooks.monitoringEnabled() ? config.monitoring.automaticStopSeconds + "s sessions" : "disabled",
                    lang.availableLocales());

        } catch (Exception failure) {
            LOGGER.error("Startup failed. No voice moderation is active, which means voice chat is running "
                    + "without restrictions until this is fixed.", failure);
            shutdownResources();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /**
     * Installs the voice chat bridge when Simple Voice Chat is present.
     *
     * The presence check is by plugin name deliberately. Touching any class
     * that names a Simple Voice Chat type before knowing it is installed makes
     * the JVM fail to resolve it, which would prevent this plugin loading at
     * all on a lobby, taking placeholders and commands down with it.
     */
    private void registerVoiceChat(VoiceBanRepository repository) throws Exception {
        if (getServer().getPluginManager().getPlugin("voicechat") == null) {
            LOGGER.info("Simple Voice Chat is not installed on this server. Running in status only mode: "
                    + "placeholders, commands and notifications are available, enforcement is not.");
            return;
        }
        hooks = VoiceChatBridge.install(this, config, repository, bans, notifications,
                lang, getDataFolder().toPath(), LOGGER);
    }

    private void registerPlaceholders() {
        if (!config.placeholders.enabled) {
            return;
        }
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            LOGGER.info("PlaceholderAPI is not installed, so voice placeholders are unavailable.");
            return;
        }
        new VoicePlaceholders(config, bans, identities, getPluginMeta().getVersion()).register();
        LOGGER.info("Registered placeholders under the codeversevoice identifier.");
    }

    private void registerCommand() {
        PluginCommand command = getCommand("voice");
        if (command == null) {
            LOGGER.error("The 'voice' command is missing from plugin.yml, so staff commands are unavailable.");
            return;
        }
        VoiceCommand executor = new VoiceCommand(this, config, bans, hooks,
                identities, sync, notifications, lang, LOGGER);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    /**
     * Periodic upkeep: retiring lapsed restrictions, ending monitoring sessions
     * that have run their course, and deleting captures past their retention
     * window. The retention sweep is the one that matters legally, so it runs on
     * the same schedule rather than on demand.
     */
    private void scheduleMaintenance() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            bans.sweepExpired();
            if (hooks instanceof VoiceChatBridge bridge) {
                bridge.purgeExpiredCaptures();
            }
            // Keeps the placeholder cache warm for everyone online, so a
            // scoreboard never has to wait on a lookup it cannot perform.
            for (Player online : Bukkit.getOnlinePlayers()) {
                bans.preload(identities.resolveThrough(online.getUniqueId()).internalId());
            }
        }, 20L * 30L, 20L * 60L * 5L);

        if (!hooks.monitoringEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskTimer(this, hooks::tick, 20L, 20L);
    }

    @Override
    public void onDisable() {
        shutdownResources();
    }

    private void shutdownResources() {
        if (voiceService != null) {
            // Withdrawn by identity, so a restart that has already published a
            // replacement is not left with the slot cleared out from under it.
            CodeverseApiProvider.unregisterService(VoiceService.class, voiceService);
            voiceService = null;
        }
        if (hooks != null) {
            hooks.shutdown();
        }
        if (sync != null) {
            sync.close();
        }
        if (identityExecutor != null) {
            identityExecutor.shutdownNow();
        }
        if (database != null) {
            database.close();
        }
    }

    public VoiceHooks hooks() {
        return hooks;
    }
}
