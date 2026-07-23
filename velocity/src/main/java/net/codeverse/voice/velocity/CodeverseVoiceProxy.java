package net.codeverse.voice.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.lang.LangManager;
import net.codeverse.voice.moderation.VoiceBanService;
import net.codeverse.voice.storage.Database;
import net.codeverse.voice.storage.IdentityLookup;
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
        version = "0.1.0",
        description = "Network wide voice moderation commands for Velocity",
        authors = {"CodeVerseHub-Minecraft Subteam"}
)
public final class CodeverseVoiceProxy {

    private static final List<String> BUNDLED_LOCALES = List.of("en", "de");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private PluginConfig config;
    private LangManager lang;
    private Database database;
    private VoiceSync sync;
    private VoiceBanService bans;

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
            IdentityLookup identities = new IdentityLookup(database, config.identity);
            if (config.identity.useAuthPluginIdentities && !identities.probe()) {
                logger.error("The accounts table '{}' could not be read. Restrictions issued here will be keyed "
                                + "to individual Minecraft accounts rather than network identities.",
                        config.identity.accountsTable);
            }

            bans = new VoiceBanService(repository, identities, config.access);

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
        if (database != null) {
            database.close();
        }
    }
}
