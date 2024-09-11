// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.ideMaven

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import org.jetbrains.idea.maven.execution.MavenRunner
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import org.slf4j.Logger
import software.aws.toolkits.core.utils.error
import software.aws.toolkits.core.utils.info
import software.aws.toolkits.jetbrains.services.codemodernizer.CodeTransformTelemetryManager
import software.aws.toolkits.jetbrains.services.codemodernizer.model.MavenCopyCommandsResult
import software.aws.toolkits.jetbrains.services.codemodernizer.model.MavenDependencyReportCommandsResult
import software.aws.toolkits.telemetry.CodeTransformBuildCommand
import software.aws.toolkits.telemetry.CodeTransformMavenBuildCommand
import software.aws.toolkits.telemetry.Result
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

// TODO: deprecated metric - remove after BI started using new metric
private fun emitMavenFailure(error: String, logger: Logger, telemetry: CodeTransformTelemetryManager, throwable: Throwable? = null) {
    if (throwable != null) logger.error(throwable) { error } else logger.error { error }
    telemetry.mvnBuildFailed(CodeTransformMavenBuildCommand.IDEBundledMaven, error)
}

fun runHilMavenCopyDependency(
    sourceFolder: File,
    destinationDir: File,
    buildlogBuilder: StringBuilder,
    logger: Logger,
    project: Project
): MavenCopyCommandsResult {
    val telemetry = CodeTransformTelemetryManager.getInstance(project)
    logger.info { "Executing IntelliJ bundled Maven" }
    try {
        // Create shared parameters
        val transformMvnRunner = TransformMavenRunner(project)
        val mvnSettings = MavenRunner.getInstance(project).settings.clone() // clone required to avoid editing user settings

        // run copy dependencies
        val copyDependenciesRunnable =
            runMavenCopyDependencies(sourceFolder, buildlogBuilder, mvnSettings, transformMvnRunner, destinationDir.toPath(), logger, telemetry)
        copyDependenciesRunnable.await()
        buildlogBuilder.appendLine(copyDependenciesRunnable.getOutput())
        if (copyDependenciesRunnable.isComplete()) {
            val successMsg = "IntelliJ IDEA bundled Maven copy-dependencies executed successfully"
            logger.info { successMsg }
            buildlogBuilder.appendLine(successMsg)
        } else if (copyDependenciesRunnable.isTerminated()) {
            return MavenCopyCommandsResult.Cancelled
        } else {
            // TODO: deprecated metric - remove after BI started using new metric
            emitMavenFailure("Maven Copy: bundled Maven failed: exitCode ${copyDependenciesRunnable.isComplete()}", logger, telemetry)
        }
    } catch (t: Throwable) {
        return MavenCopyCommandsResult.Failure
    }
    // When all commands executed successfully, show the transformation hub
    return MavenCopyCommandsResult.Success(destinationDir)
}

fun runMavenVerifyCommand(sourceFolder: File, buildLogBuilder: StringBuilder, logger: Logger, project: Project): MavenCopyCommandsResult {
    val currentTimestamp = System.currentTimeMillis()
    val destinationDir = Files.createTempDirectory("qct_local_build_temp_$currentTimestamp")
    val telemetry = CodeTransformTelemetryManager.getInstance(project)
    var telemetryErrorMessage = ""
    var telemetryLocalBuildResult = Result.Succeeded
    logger.info { "Executing IntelliJ bundled Maven for local build" }
    try {
        // Create shared parameters
        val transformMvnRunner = TransformMavenRunner(project)
        val mvnSettings = MavenRunner.getInstance(project).settings.clone() // clone required to avoid editing user settings

        // run verify
        val verifyRunnable =
            runMavenVerify(sourceFolder, buildLogBuilder, mvnSettings, transformMvnRunner, logger, destinationDir)
        verifyRunnable.await()
        buildLogBuilder.appendLine(verifyRunnable.getOutput())
        if (verifyRunnable.isComplete()) {
            val successMsg = "IntelliJ IDEA bundled Maven copy-dependencies executed successfully"
            logger.info { successMsg }
            buildLogBuilder.appendLine(successMsg)
        } else if (verifyRunnable.isTerminated()) {
            telemetryLocalBuildResult = Result.Cancelled
            return MavenCopyCommandsResult.Cancelled
        } else {
            telemetryErrorMessage += "Maven Verify: bundled Maven failed. "
        }
    } catch (t: Throwable) {
        val errorMessage = "IntelliJ bundled Maven execution failed: ${t.message}"
        logger.error(t) { errorMessage }
        telemetryErrorMessage = errorMessage
        telemetryLocalBuildResult = Result.Failed
        return MavenCopyCommandsResult.Failure
    } finally {
        // emit telemetry
        telemetry.localBuildProject(CodeTransformBuildCommand.IDEBundledMaven, telemetryLocalBuildResult, telemetryErrorMessage)
    }
    // TODO: look at this destination dir, it should contain the output files of mvn verify
    return MavenCopyCommandsResult.Success(destinationDir.toFile())
}

fun runMavenCopyCommands(sourceFolder: File, buildLogBuilder: StringBuilder, logger: Logger, project: Project): MavenCopyCommandsResult {
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
            runMavenCopyDependencies(sourceFolder, buildLogBuilder, mvnSettings, transformMvnRunner, destinationDir, logger, telemetry)
        copyDependenciesRunnable.await()
        buildLogBuilder.appendLine(copyDependenciesRunnable.getOutput())
        if (copyDependenciesRunnable.isComplete()) {
            val successMsg = "IntelliJ IDEA bundled Maven copy-dependencies executed successfully"
            logger.info { successMsg }
            buildLogBuilder.appendLine(successMsg)
        } else if (copyDependenciesRunnable.isTerminated()) {
            telemetryLocalBuildResult = Result.Cancelled
            return MavenCopyCommandsResult.Cancelled
        } else {
            telemetryErrorMessage += "Maven Copy: bundled Maven failed. "

            // TODO: deprecated metric - remove after BI started using new metric
            emitMavenFailure("Maven Copy: bundled Maven failed: exitCode ${copyDependenciesRunnable.isComplete()}", logger, telemetry)
        }

        // Run clean
        val cleanRunnable = runMavenClean(sourceFolder, buildLogBuilder, mvnSettings, transformMvnRunner, logger, telemetry, destinationDir)
        cleanRunnable.await()
        buildLogBuilder.appendLine(cleanRunnable.getOutput())
        if (cleanRunnable.isComplete()) {
            val successMsg = "IntelliJ bundled Maven clean executed successfully"
            logger.info { successMsg }
            buildLogBuilder.appendLine(successMsg)
        } else if (cleanRunnable.isTerminated()) {
            telemetryLocalBuildResult = Result.Cancelled
            return MavenCopyCommandsResult.Cancelled
        } else {
            telemetryErrorMessage += "Maven Clean: bundled Maven failed."

            // TODO: deprecated metric - remove after BI started using new metric
            emitMavenFailure("Maven Clean: bundled Maven failed: exitCode ${cleanRunnable.isComplete()}", logger, telemetry)

            telemetryLocalBuildResult = Result.Failed
            return MavenCopyCommandsResult.Failure
        }

        // Run install
        val installRunnable = runMavenInstall(sourceFolder, buildLogBuilder, mvnSettings, transformMvnRunner, logger, telemetry, destinationDir)
        installRunnable.await()
        buildLogBuilder.appendLine(installRunnable.getOutput())
        if (installRunnable.isComplete()) {
            val successMsg = "IntelliJ bundled Maven install executed successfully"
            logger.info { successMsg }
            buildLogBuilder.appendLine(successMsg)
        } else if (installRunnable.isTerminated()) {
            telemetryLocalBuildResult = Result.Cancelled
            return MavenCopyCommandsResult.Cancelled
        } else {
            telemetryErrorMessage += "Maven Install: bundled Maven failed."

            // TODO: deprecated metric - remove after BI started using new metric
            emitMavenFailure("Maven Install: bundled Maven failed: exitCode ${installRunnable.isComplete()}", logger, telemetry)

            telemetryLocalBuildResult = Result.Failed
            return MavenCopyCommandsResult.Failure
        }
    } catch (t: Throwable) {
        // TODO: deprecated metric - remove after BI started using new metric
        emitMavenFailure("IntelliJ bundled Maven executed failed: ${t.message}", logger, telemetry, t)

        val errorMessage = "IntelliJ bundled Maven executed failed: ${t.message}"
        logger.error(t) { errorMessage }
        telemetryErrorMessage = errorMessage
        telemetryLocalBuildResult = Result.Failed
        return MavenCopyCommandsResult.Failure
    } finally {
        // emit telemetry
        telemetry.localBuildProject(CodeTransformBuildCommand.IDEBundledMaven, telemetryLocalBuildResult, telemetryErrorMessage)
    }
    // When all commands executed successfully, show the transformation hub
    return MavenCopyCommandsResult.Success(destinationDir.toFile())
}

private fun runMavenCopyDependencies(
    sourceFolder: File,
    buildLogBuilder: StringBuilder,
    mvnSettings: MavenRunnerSettings,
    transformMavenRunner: TransformMavenRunner,
    destinationDir: Path,
    logger: Logger,
    telemetry: CodeTransformTelemetryManager,
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

            // TODO: deprecated metric - remove after BI started using new metric
            telemetry.mvnBuildFailed(CodeTransformMavenBuildCommand.IDEBundledMaven, error)
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
    telemetry: CodeTransformTelemetryManager,
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

            // TODO: deprecated metric - remove after BI started using new metric
            emitMavenFailure(error, logger, telemetry, t)
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
    telemetry: CodeTransformTelemetryManager,
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

            // TODO: deprecated metric - remove after BI started using new metric
            emitMavenFailure(error, logger, telemetry, t)
        }
    }
    return installTransformRunnable
}

private fun runMavenVerify(
    sourceFolder: File,
    buildLogBuilder: StringBuilder,
    mvnSettings: MavenRunnerSettings,
    transformMavenRunner: TransformMavenRunner,
    logger: Logger,
    destinationDir: Path
): TransformRunnable {
    buildLogBuilder.appendLine("Command Run: IntelliJ IDEA bundled Maven verify")
    val verifyParams = MavenRunnerParameters(
        false,
        sourceFolder.absolutePath,
        null,
        listOf("-DoutputDirectory=$destinationDir", "verify"), // TODO: look at this destination dir, it should contain the output files of mvn verify
        emptyList<String>(),
        null
    )
    // verifyParams.workingDirPath = "" // TODO: set this to the path to the cloned project; this is N/A if using Git every time to switch the branch
    mvnSettings.setJreName("My JDK 17") // TODO: get this from the user via chat, see below
    val jdkTable = ProjectJdkTable.getInstance()
    val sdkNames = jdkTable.allJdks.map { it.name } // TODO: show these to the user in a dropdown form in chat, tell them to choose their JDK 17

    val verifyTransformRunnable = TransformRunnable()
    runInEdt {
        try {
            transformMavenRunner.run(verifyParams, mvnSettings, verifyTransformRunnable)
        } catch (t: Throwable) {
            val error = "Maven Verify: Unexpected error when executing bundled Maven verify"
            logger.error { error }
            verifyTransformRunnable.setExitCode(Integer.MIN_VALUE) // to stop looking for the exitCode
            buildLogBuilder.appendLine("IntelliJ bundled Maven verify failed: ${t.message}")
        }
    }
    return verifyTransformRunnable
}

private fun runMavenDependencyUpdatesReport(
    sourceFolder: File,
    buildlogBuilder: StringBuilder,
    mvnSettings: MavenRunnerSettings,
    transformMavenRunner: TransformMavenRunner,
    logger: Logger,
    telemetry: CodeTransformTelemetryManager,
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
            val error = "Maven dependency report: Unexpected error when executing bundled Maven dependency updates report"
            dependencyUpdatesReportRunnable.setExitCode(Integer.MIN_VALUE) // to stop looking for the exitCode
            buildlogBuilder.appendLine("IntelliJ bundled Maven dependency updates report failed: ${t.message}")

            // TODO: deprecated metric - remove after BI started using new metric
            emitMavenFailure(error, logger, telemetry, t)
        }
    }
    return dependencyUpdatesReportRunnable
}

fun runDependencyReportCommands(sourceFolder: File, buildlogBuilder: StringBuilder, logger: Logger, project: Project): MavenDependencyReportCommandsResult {
    val telemetry = CodeTransformTelemetryManager.getInstance(project)
    logger.info { "Executing IntelliJ bundled Maven" }

    val transformMvnRunner = TransformMavenRunner(project)
    val mvnSettings = MavenRunner.getInstance(project).settings.clone() // clone required to avoid editing user settings

    val runnable = runMavenDependencyUpdatesReport(sourceFolder, buildlogBuilder, mvnSettings, transformMvnRunner, logger, telemetry)
    runnable.await()
    buildlogBuilder.appendLine(runnable.getOutput())
    if (runnable.isComplete()) {
        val successMsg = "IntelliJ bundled Maven dependency report executed successfully"
        logger.info { successMsg }
        buildlogBuilder.appendLine(successMsg)
    } else if (runnable.isTerminated()) {
        return MavenDependencyReportCommandsResult.Cancelled
    } else {
        // TODO: deprecated metric - remove after BI started using new metric
        emitMavenFailure("Maven dependency report: bundled Maven failed: exitCode ${runnable.isComplete()}", logger, telemetry)

        return MavenDependencyReportCommandsResult.Failure
    }

    return MavenDependencyReportCommandsResult.Success
}
