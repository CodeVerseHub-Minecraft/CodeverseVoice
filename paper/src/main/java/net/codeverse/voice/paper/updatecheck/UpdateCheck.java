package net.codeverse.voice.paper.updatecheck;

import net.codeverse.updater.UpdateResult;
import net.codeverse.updater.Updater;
import net.codeverse.updater.UpdaterConfig;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Wires the update library into the voice plugin, so startup stays short.
 *
 * Reports each outcome the library distinguishes. Auto apply defaults off, the
 * same conservative default the other Codeverse plugins take, since a voice
 * plugin still enforces moderation and an unattended swap of an enforcement
 * plugin is not worth the convenience.
 */
public final class UpdateCheck {

    private UpdateCheck() {
    }

    public static void run(String currentVersion,
                           Path updateFolder,
                           boolean autoApply,
                           Executor executor,
                           Logger logger) {
        Updater updater = new Updater(UpdaterConfig
                .forRepository("CodeVerseHub-Minecraft", "CodeverseVoice")
                .currentVersion(currentVersion)
                .updateFolder(updateFolder)
                .targetJarName("CodeverseVoice-Paper-" + currentVersion + ".jar")
                .autoApply(autoApply)
                .checkInterval(Duration.ofHours(6))
                .build());

        updater.checkAsync(executor, result -> {
            switch (result) {
                case UpdateResult.UpToDate ignored ->
                        logger.info("CodeverseVoice is up to date.");
                case UpdateResult.UpdateAvailable available ->
                        logger.info("CodeverseVoice {} is available (running {}). Auto apply is off, so "
                                        + "nothing was staged. Update from the release page when ready.",
                                available.release().tag(), currentVersion);
                case UpdateResult.Staged staged ->
                        logger.info("CodeverseVoice {} was downloaded, verified and staged. Restart to apply.",
                                staged.release().tag());
                case UpdateResult.Failed failed ->
                        logger.warn("Update check did not complete: {}", failed.reason());
            }
        });
    }
}
