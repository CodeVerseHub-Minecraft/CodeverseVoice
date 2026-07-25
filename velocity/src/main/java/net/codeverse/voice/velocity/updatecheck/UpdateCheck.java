package net.codeverse.voice.velocity.updatecheck;

import net.codeverse.updater.UpdateResult;
import net.codeverse.updater.Updater;
import net.codeverse.updater.UpdaterConfig;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * The proxy half of the voice update check.
 *
 * Separate from the Paper helper because the two platforms take different jars
 * from the same release. Staging the backend jar onto a proxy would replace a
 * working plugin with one that cannot load, so the jar name is the part that
 * must not be shared between them.
 */
public final class UpdateCheck {

    private UpdateCheck() {
    }

    public static void run(String currentVersion,
                           Path updateFolder,
                           boolean autoApply,
                           int checkIntervalHours,
                           Executor executor,
                           Logger logger) {
        Updater updater = new Updater(UpdaterConfig
                .forRepository("CodeVerseHub-Minecraft", "CodeverseVoice")
                .currentVersion(currentVersion)
                .updateFolder(updateFolder)
                .targetJarName("CodeverseVoice-Velocity-" + currentVersion + ".jar")
                .autoApply(autoApply)
                .checkInterval(Duration.ofHours(checkIntervalHours))
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
