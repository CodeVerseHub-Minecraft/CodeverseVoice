package net.codeverse.voice.velocity.updatecheck;

import net.codeverse.updater.UpdateResult;
import net.codeverse.updater.Updater;
import net.codeverse.updater.UpdaterConfig;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * The proxy half of the voice update check. Reports only; it never stages.
 *
 * Velocity has no update folder. Paper watches plugins/update and swaps a jar
 * in on the next boot; the proxy has no equivalent, and a jar left in such a
 * folder is ignored forever. Reporting a staged update that will never apply is
 * worse than not offering staging, because an operator who believes it is
 * handled stops checking.
 */
public final class UpdateCheck {

    private UpdateCheck() {
    }

    public static void run(String currentVersion,
                           Path dataDirectory,
                           boolean autoApplyRequested,
                           int checkIntervalHours,
                           Executor executor,
                           Logger logger) {
        if (autoApplyRequested) {
            logger.warn("updates.autoApply is enabled, but Velocity has no update folder, so a staged "
                    + "jar would never be applied. Updates will be reported only.");
        }
        Updater updater = new Updater(UpdaterConfig
                .forRepository("CodeVerseHub-Minecraft", "CodeverseVoice")
                .currentVersion(currentVersion)
                .updateFolder(dataDirectory)
                .targetJarName("CodeverseVoice-Velocity-" + currentVersion + ".jar")
                .autoApply(false)
                .checkInterval(Duration.ofHours(checkIntervalHours))
                .build());

        updater.checkAsync(executor, result -> {
            switch (result) {
                case UpdateResult.UpToDate ignored ->
                        logger.info("CodeverseVoice is up to date.");
                case UpdateResult.UpdateAvailable available ->
                        logger.info("CodeverseVoice {} is available (running {}). Download it from "
                                        + "https://github.com/CodeVerseHub-Minecraft/CodeverseVoice/releases "
                                        + "and replace the jar in plugins, then restart.",
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
