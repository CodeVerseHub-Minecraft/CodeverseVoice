package net.codeverse.voice.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.lang.LangManager;
import net.codeverse.jdbc.JdbcIdentityService;
import net.codeverse.voice.velocity.updatecheck.UpdateCheck;
import net.codeverse.voice.moderation.VoiceBanService;
import net.codeverse.voice.storage.Database;
import net.codeverse.voice.storage.IdentityResolver;
import net.codeverse.voice.storage.VoiceBanRepository;
import net.codeverse.voice.sync.VoiceSync;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Proxy side moderation.
 *
 * Voice itself runs on a single backend, but staff are rarely standing on it
 * when something needs dealing with. This module lets a restriction be issued
 * from anywhere on the network: it writes the same rows, publishes the same
 * Redis message, and the voice server picks the change up immediately.
 *
 * It deliberately does not touch audio. Simple Voice Chat has no proxy
 * component, and pretending otherwise would mean inventing a protocol whose
 * failure modes nobody could debug.
 */
@Plugin(
        id = "codeverse-voice-proxy",
        name = "Codeverse Voice Proxy",
        version = "0.3.1",
        description = "Network wide voice moderation commands for Velocity",
        authors = {"CodeVerseHub-Minecraft Subteam"},
        // Not optional. This jar deliberately excludes the shared API classes
        // so it uses the same copy CodeverseAuth registered, which means
        // without Auth installed those classes exist nowhere on the proxy and
        // this plugin cannot load at all. Declaring the dependency makes
        // Velocity refuse it with a clear message and in the right order,
        // rather than failing at init with a NoClassDefFoundError that is an
        // Error and so escapes the startup catch, leaving the pool open.
        dependencies = {@Dependency(id = "codeverse-auth")}
)
public final class CodeverseVoiceProxy {

    private static final List<String> BUNDLED_LOCALES = List.of("en", "de");
    private static final String PLUGIN_ID = "codeverse-voice-proxy";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private PluginConfig config;
    private LangManager lang;
    private Database database;
    private VoiceSync sync;
    private VoiceBanService bans;
    private JdbcIdentityService identityService;
    private java.util.concurrent.ExecutorService identityExecutor;

    @Inject
    public CodeverseVoiceProxy(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            config = PluginConfig.load(dataDirectory);
            lang = new LangManager(dataDirectory, config.language.defaultLocale,
                    config.language.usePlayerLocale, BUNDLED_LOCALES);

            database = new Database(config.storage);
            database.applySchema();

            VoiceBanRepository repository = new VoiceBanRepository(database);

            // The proxy resolves identity from the database directly rather
            // than through the API that CodeverseAuth registers. The two would
            // usually agree, but depending on the registration would couple
            // this plugin's startup to another plugin's load order, and a
            // missing provider would leave moderation unable to resolve anyone.
            // Reading the shared rows is the same answer without that coupling.
            identityExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            identityService = new JdbcIdentityService(
                    database.dataSource(),
                    identityExecutor,
                    config.identity.accountsTable,
                    java.time.Duration.ofSeconds(Math.max(30, config.identity.cacheSeconds)));

            boolean linkage = config.identity.useAuthPluginIdentities && identityService.probe();
            if (config.identity.useAuthPluginIdentities && !linkage) {
                logger.error("The accounts table '{}' could not be read. Restrictions issued here will be keyed "
                                + "to individual Minecraft accounts rather than network identities.",
                        config.identity.accountsTable);
            }
            IdentityResolver identities = new IdentityResolver(identityService, linkage);

            bans = new VoiceBanService(repository, identityService, linkage, config.access);

            sync = new VoiceSync(config.redis);
            if (config.redis.enabled && !sync.start(bans::invalidate, bans::invalidateAll)) {
                logger.warn("Redis is unreachable. Restrictions issued here will still be written, but the "
                        + "voice server will not apply them until its own cache expires.");
            }

            proxy.getCommandManager().register(
                    proxy.getCommandManager().metaBuilder("voice").aliases("vc").plugin(this).build(),
                    new ProxyVoiceCommand(bans, identities, sync, lang, proxy, logger).build());

            proxy.getScheduler().buildTask(this, () -> bans.sweepExpired())
                    .repeat(15, TimeUnit.MINUTES)
                    .schedule();

            if (config.updates.checkOnStartup) {
                // The proxy checks separately from the backend even though both
                // come from one release, because they take different jars from
                // it. The version is read from the proxy rather than held in a
                // constant, so it cannot drift from what is actually running.
                String runningVersion = proxy.getPluginManager().getPlugin(PLUGIN_ID)
                        .flatMap(container -> container.getDescription().getVersion())
                        .orElse(null);
                if (runningVersion == null) {
                    logger.warn("The proxy did not report this plugin's version, so update checks "
                            + "are disabled for this session.");
                } else {
                    proxy.getScheduler().buildTask(this, () -> UpdateCheck.run(
                                    runningVersion, dataDirectory, config.updates.autoApply,
                                    config.updates.checkIntervalHours, Runnable::run, logger))
                            .repeat(config.updates.checkIntervalHours, TimeUnit.HOURS)
                            .schedule();
                }
            }

            logger.info("Proxy voice moderation ready. Identities {}, sync {}",
                    identities.isUsingAuthIdentities() ? "linked to network accounts" : "per Minecraft account",
                    sync.isHealthy() ? "connected" : "offline");

        } catch (Exception failure) {
            logger.error("Startup failed. Proxy side voice moderation is unavailable; use the commands on the "
                    + "voice server instead.", failure);
            shutdown();
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        shutdown();
    }

    private void shutdown() {
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
}
