// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.ideMaven

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.project.Project
import org.jetbrains.idea.maven.execution.MavenRunner
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import org.slf4j.Logger
import software.aws.toolkits.core.utils.error
import software.aws.toolkits.core.utils.info
import software.aws.toolkits.jetbrains.services.codemodernizer.CodeTransformTelemetryManager
import software.aws.toolkits.jetbrains.services.codemodernizer.model.LocalBuildResult
import software.aws.toolkits.jetbrains.services.codemodernizer.model.MavenDependencyReportCommandsResult
import software.aws.toolkits.jetbrains.utils.notifyStickyInfo
import software.aws.toolkits.resources.message
import software.aws.toolkits.telemetry.CodeTransformBuildCommand
import software.aws.toolkits.telemetry.Result
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

fun runHilMavenCopyDependency(
    sourceFolder: File,
    destinationDir: File,
    buildLogBuilder: StringBuilder,
    logger: Logger,
    project: Project
): LocalBuildResult {
    val telemetry = CodeTransformTelemetryManager.getInstance(project)
    var telemetryErrorMessage = ""
    var telemetryLocalBuildResult = Result.Succeeded
    var copyDependenciesRunnable: TransformRunnable? = null
    logger.info { "Executing IntelliJ bundled Maven" }
    try {
        // Create shared parameters
        val transformMvnRunner = TransformMavenRunner(project)
        val mvnSettings = MavenRunner.getInstance(project).settings.clone() // clone required to avoid editing user settings

        // run copy dependencies
        copyDependenciesRunnable = runMavenCopyDependencies(sourceFolder, buildLogBuilder, mvnSettings, transformMvnRunner, destinationDir.toPath(), logger)
        copyDependenciesRunnable.await()
        buildLogBuilder.appendLine(copyDependenciesRunnable.getOutput())
        if (copyDependenciesRunnable.isComplete()) {
            val successMsg = "IntelliJ IDEA bundled Maven copy-dependencies executed successfully"
            logger.info { successMsg }
            buildLogBuilder.appendLine(successMsg)
        } else if (copyDependenciesRunnable.isTerminated()) {
            telemetryErrorMessage += "Maven Copy: bundled Maven cancelled."
            telemetryLocalBuildResult = Result.Cancelled
            return LocalBuildResult.Cancelled
        } else {
            telemetryErrorMessage += "Maven Copy: bundled Maven failed."
            telemetryLocalBuildResult = Result.Failed
        }
    } catch (t: Throwable) {
        return LocalBuildResult.Failure(message("codemodernizer.notification.warn.maven_failed.content"))
    } finally {
        telemetry.localBuildProject(CodeTransformBuildCommand.IDEBundledMaven, telemetryLocalBuildResult, telemetryErrorMessage)
    }
    // When all commands executed successfully, show the transformation hub
    return LocalBuildResult.Success(destinationDir)
}

fun runMavenCopyCommands(sourceFolder: File, buildLogBuilder: StringBuilder, logger: Logger, project: Project): LocalBuildResult {
    val currentTimestamp = System.currentTimeMillis()
    val destinationDir = Files.createTempDirectory("transformation_dependencies_temp_$currentTimestamp")
    val telemetry = CodeTransformTelemetryManager.getInstance(project)
    var telemetryErrorMessage = ""
    var telemetryLocalBuildResult = Result.Succeeded

    logger.info { "Executing IntelliJ bundled Maven" }
    try {
        // Create shared parameters
        val transformMvnRunner = TransformMavenRunner(project)
        val mvnSettings = MavenRunner.getInstance(project).settings.clone() // clone required to avoid editing user settings

        // run copy dependencies
        val copyDependenciesRunnable =
            runMavenCopyDependencies(sourceFolder, buildLogBuilder, mvnSettings, transformMvnRunner, destinationDir, logger)
        copyDependenciesRunnable.await()
        buildLogBuilder.appendLine(copyDependenciesRunnable.getOutput())
        if (copyDependenciesRunnable.isComplete()) {
            val successMsg = "IntelliJ IDEA bundled Maven copy-dependencies executed successfully"
            logger.info { successMsg }
            buildLogBuilder.appendLine(successMsg)
        } else if (copyDependenciesRunnable.isTerminated()) {
            telemetryErrorMessage += "Maven Copy: bundled Maven cancelled."
            telemetryLocalBuildResult = Result.Cancelled
            return LocalBuildResult.Cancelled
        } else {
            telemetryErrorMessage += "Maven Copy: bundled Maven failed. "
        }

        // Run clean
        val cleanRunnable = runMavenClean(sourceFolder, buildLogBuilder, mvnSettings, transformMvnRunner, logger, destinationDir)
        cleanRunnable.await()
        buildLogBuilder.appendLine(cleanRunnable.getOutput())
        if (cleanRunnable.isComplete()) {
            val successMsg = "IntelliJ bundled Maven clean executed successfully"
            logger.info { successMsg }
            buildLogBuilder.appendLine(successMsg)
        } else if (cleanRunnable.isTerminated()) {
            telemetryErrorMessage += "Maven Clean: bundled Maven cancelled."
            telemetryLocalBuildResult = Result.Cancelled
            return LocalBuildResult.Cancelled
        } else {
            telemetryErrorMessage += "Maven Clean: bundled Maven failed."
            telemetryLocalBuildResult = Result.Failed
            return LocalBuildResult.Failure(message("codemodernizer.notification.warn.maven_failed.content"))
        }

        // Run install
        val installRunnable = runMavenInstall(sourceFolder, buildLogBuilder, mvnSettings, transformMvnRunner, logger, destinationDir)
        installRunnable.await()
        buildLogBuilder.appendLine(installRunnable.getOutput())
        if (installRunnable.isComplete()) {
            val successMsg = "IntelliJ bundled Maven install executed successfully"
            logger.info { successMsg }
            buildLogBuilder.appendLine(successMsg)
        } else if (installRunnable.isTerminated()) {
            telemetryErrorMessage += "Maven Install: bundled Maven cancelled."
            telemetryLocalBuildResult = Result.Cancelled
            return LocalBuildResult.Cancelled
        } else {
            telemetryErrorMessage += "Maven Install: bundled Maven failed."
            telemetryLocalBuildResult = Result.Failed
            return LocalBuildResult.Failure(message("codemodernizer.notification.warn.maven_failed.content"))
        }
    } catch (t: Throwable) {
        val errorMessage = "IntelliJ bundled Maven executed failed: ${t.message}"
        logger.error(t) { errorMessage }
        telemetryErrorMessage = errorMessage
        telemetryLocalBuildResult = Result.Failed
        return LocalBuildResult.Failure(message("codemodernizer.notification.warn.maven_failed.content"))
    } finally {
        // emit telemetry
        telemetry.localBuildProject(CodeTransformBuildCommand.IDEBundledMaven, telemetryLocalBuildResult, telemetryErrorMessage)
    }
    // When all commands executed successfully, show the transformation hub
    return LocalBuildResult.Success(destinationDir.toFile())
}

private fun runMavenCopyDependencies(
    sourceFolder: File,
    buildLogBuilder: StringBuilder,
    mvnSettings: MavenRunnerSettings,
    transformMavenRunner: TransformMavenRunner,
    destinationDir: Path,
    logger: Logger,
): TransformRunnable {
    buildLogBuilder.appendLine("Command Run: IntelliJ IDEA bundled Maven dependency:copy-dependencies")
    val copyCommandList = listOf(
        "dependency:copy-dependencies",
        "-DoutputDirectory=$destinationDir",
        "-Dmdep.useRepositoryLayout=true",
        "-Dmdep.copyPom=true",
        "-Dmdep.addParentPoms=true",
    )
    val copyParams = MavenRunnerParameters(
        false,
        sourceFolder.absolutePath,
        null,
        copyCommandList,
        emptyList<String>(),
        null
    )
    val copyTransformRunnable = TransformRunnable()
    runInEdt {
        try {
            transformMavenRunner.run(copyParams, mvnSettings, copyTransformRunnable)
        } catch (t: Throwable) {
            val error = "Maven Copy: Unexpected error when executing bundled Maven copy dependencies"
            copyTransformRunnable.setExitCode(Integer.MIN_VALUE) // to stop looking for the exitCode
            logger.info(t) { error }
            buildLogBuilder.appendLine("IntelliJ bundled Maven copy dependencies failed: ${t.message}")
        }
    }
    return copyTransformRunnable
}

private fun runMavenClean(
    sourceFolder: File,
    buildLogBuilder: StringBuilder,
    mvnSettings: MavenRunnerSettings,
    transformMavenRunner: TransformMavenRunner,
    logger: Logger,
    destinationDir: Path
): TransformRunnable {
    buildLogBuilder.appendLine("Command Run: IntelliJ IDEA bundled Maven clean")
    val cleanParams = MavenRunnerParameters(
        false,
        sourceFolder.absolutePath,
        null,
        listOf("-Dmaven.repo.local=$destinationDir", "clean"),
        emptyList<String>(),
        null
    )
    val cleanTransformRunnable = TransformRunnable()
    runInEdt {
        try {
            transformMavenRunner.run(cleanParams, mvnSettings, cleanTransformRunnable)
        } catch (t: Throwable) {
            val error = "Maven Clean: Unexpected error when executing bundled Maven clean"
            logger.error { error }
            cleanTransformRunnable.setExitCode(Integer.MIN_VALUE) // to stop looking for the exitCode
            buildLogBuilder.appendLine("IntelliJ bundled Maven clean failed: ${t.message}")
        }
    }
    return cleanTransformRunnable
}

private fun runMavenInstall(
    sourceFolder: File,
    buildLogBuilder: StringBuilder,
    mvnSettings: MavenRunnerSettings,
    transformMavenRunner: TransformMavenRunner,
    logger: Logger,
    destinationDir: Path
): TransformRunnable {
    buildLogBuilder.appendLine("Command Run: IntelliJ IDEA bundled Maven install")
    val installParams = MavenRunnerParameters(
        false,
        sourceFolder.absolutePath,
        null,
        listOf("-Dmaven.repo.local=$destinationDir", "install"),
        emptyList<String>(),
        null
    )
    val installTransformRunnable = TransformRunnable()
    runInEdt {
        try {
            transformMavenRunner.run(installParams, mvnSettings, installTransformRunnable)
        } catch (t: Throwable) {
            val error = "Maven Install: Unexpected error when executing bundled Maven install"
            logger.error { error }
            installTransformRunnable.setExitCode(Integer.MIN_VALUE) // to stop looking for the exitCode
            buildLogBuilder.appendLine("IntelliJ bundled Maven install failed: ${t.message}")
        }
    }
    return installTransformRunnable
}

private fun runMavenDependencyUpdatesReport(
    sourceFolder: File,
    buildlogBuilder: StringBuilder,
    mvnSettings: MavenRunnerSettings,
    transformMavenRunner: TransformMavenRunner,
): TransformRunnable {
    buildlogBuilder.appendLine("Command Run: IntelliJ IDEA bundled Maven dependency updates report")

    val dependencyUpdatesReportCommandList = listOf(
        "versions:dependency-updates-aggregate-report",
        "-DonlyProjectDependencies=true",
        "-DdependencyUpdatesReportFormats=xml",
    )

    val params = MavenRunnerParameters(
        false,
        sourceFolder.absolutePath,
        null,
        dependencyUpdatesReportCommandList,
        emptyList<String>(),
        null
    )
    val dependencyUpdatesReportRunnable = TransformRunnable()
    runInEdt {
        try {
            transformMavenRunner.run(params, mvnSettings, dependencyUpdatesReportRunnable)
        } catch (t: Throwable) {
            dependencyUpdatesReportRunnable.setExitCode(Integer.MIN_VALUE) // to stop looking for the exitCode
            buildlogBuilder.appendLine("IntelliJ bundled Maven dependency updates report failed: ${t.message}")
        }
    }
    return dependencyUpdatesReportRunnable
}

fun runDependencyReportCommands(sourceFolder: File, buildlogBuilder: StringBuilder, logger: Logger, project: Project): MavenDependencyReportCommandsResult {
    val telemetry = CodeTransformTelemetryManager.getInstance(project)
    logger.info { "Executing IntelliJ bundled Maven" }

    val transformMvnRunner = TransformMavenRunner(project)
    val mvnSettings = MavenRunner.getInstance(project).settings.clone() // clone required to avoid editing user settings

    val runnable = runMavenDependencyUpdatesReport(sourceFolder, buildlogBuilder, mvnSettings, transformMvnRunner)
    runnable.await()
    buildlogBuilder.appendLine(runnable.getOutput())
    if (runnable.isComplete()) {
        val successMsg = "IntelliJ bundled Maven dependency report executed successfully"
        logger.info { successMsg }
        buildlogBuilder.appendLine(successMsg)
    } else if (runnable.isTerminated()) {
        return MavenDependencyReportCommandsResult.Cancelled
    } else {
        return MavenDependencyReportCommandsResult.Failure
    }
    return MavenDependencyReportCommandsResult.Success
}
