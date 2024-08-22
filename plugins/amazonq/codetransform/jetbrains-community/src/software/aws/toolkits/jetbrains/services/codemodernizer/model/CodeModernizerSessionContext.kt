// Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import org.slf4j.Logger
import software.aws.toolkits.core.utils.createTemporaryZipFile
import software.aws.toolkits.core.utils.error
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.core.utils.info
import software.aws.toolkits.core.utils.putNextEntry
import software.aws.toolkits.core.utils.warn
import software.aws.toolkits.jetbrains.AwsPlugin
import software.aws.toolkits.jetbrains.AwsToolkit
import software.aws.toolkits.jetbrains.services.codemodernizer.CodeTransformTelemetryManager
import software.aws.toolkits.jetbrains.services.codemodernizer.constants.HIL_DEPENDENCIES_ROOT_NAME
import software.aws.toolkits.jetbrains.services.codemodernizer.constants.HIL_MANIFEST_FILE_NAME
import software.aws.toolkits.jetbrains.services.codemodernizer.ideMaven.runDependencyReportCommands
import software.aws.toolkits.jetbrains.services.codemodernizer.ideMaven.runHilMavenCopyDependency
import software.aws.toolkits.jetbrains.services.codemodernizer.ideMaven.runMavenCopyCommands
import software.aws.toolkits.jetbrains.services.codemodernizer.panels.managers.CodeModernizerBottomWindowPanelManager
import software.aws.toolkits.jetbrains.services.codemodernizer.toolwindow.CodeModernizerBottomToolWindowFactory
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.getPathToHilArtifactPomFolder
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.getPathToHilDependenciesRootDir
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.getPathToHilUploadZip
import software.aws.toolkits.resources.message
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipOutputStream
import kotlin.io.path.Path
import kotlin.io.path.pathString

const val MANIFEST_PATH = "manifest.json"
const val ZIP_SOURCES_PATH = "sources"
const val ZIP_DEPENDENCIES_PATH = "dependencies"
const val BUILD_LOG_PATH = "build-logs.txt"
const val MAVEN_BUILD_SYSTEM = "maven"
const val GRADLE_BUILD_SYSTEM = "gradle"
const val MAVEN_CONFIGURATION_FILE_NAME = "pom.xml"
const val GRADLE_CONFIGURATION_FILE_NAME = "build.gradle"
const val GRADLE_KTS_CONFIGURATION_FILE_NAME = "build.gradle.kts"
const val MAVEN_DEFAULT_BUILD_DIRECTORY_NAME = "target"
val GRADLE_BUILD_DIRECTORY_NAMES = listOf("build", ".gradle", "bin", "START")
const val IDEA_DIRECTORY_NAME = ".idea"
const val INVALID_SUFFIX_SHA = ".sha1"
const val INVALID_SUFFIX_REPOSITORIES = ".repositories"
const val INVALID_SUFFIX_LOCK = ".lock"
const val INVALID_SUFFIX_GC_PROPERTIES = "gc.properties"
const val INVALID_SUFFIX_DLL = ".dll"
data class CodeModernizerSessionContext(
    val project: Project,
    val configurationFile: VirtualFile,
    val sourceJavaVersion: JavaSdkVersion,
    val targetJavaVersion: JavaSdkVersion,
) {
    private val mapper = jacksonObjectMapper()
    private val ignoredFileExtensions = setOf(INVALID_SUFFIX_SHA, INVALID_SUFFIX_REPOSITORIES, INVALID_SUFFIX_LOCK, INVALID_SUFFIX_GC_PROPERTIES, INVALID_SUFFIX_DLL)

    fun File.isMavenTargetFolder(): Boolean {
        val hasPomSibling = this.resolveSibling(MAVEN_CONFIGURATION_FILE_NAME).exists()
        val isMavenTargetDirName = this.isDirectory && this.name == MAVEN_DEFAULT_BUILD_DIRECTORY_NAME
        return isMavenTargetDirName && hasPomSibling
    }

    fun File.isGradleBuildFolder(): Boolean {
        // TO-DO: maybe remove hasGradleSibling?
        // print out the ZIP path and make sure everything is being excluded
        val hasGradleSibling = this.resolveSibling(GRADLE_CONFIGURATION_FILE_NAME).exists() || this.resolveSibling(GRADLE_KTS_CONFIGURATION_FILE_NAME).exists()
        val isGradleFolder = this.isDirectory && this.name in GRADLE_BUILD_DIRECTORY_NAMES
        return isGradleFolder && hasGradleSibling
    }

    fun File.isIdeaFolder(): Boolean =
        this.isDirectory && this.name == IDEA_DIRECTORY_NAME


    private fun findDirectoriesToExclude(sourceFolder: File): List<File> {
        val excluded = mutableListOf<File>()
        sourceFolder.walkTopDown().onEnter {
            if (it.isIdeaFolder()) {
                excluded.add(it)
                return@onEnter false
            }
            if (it.isMavenTargetFolder() && configurationFile.name == "pom.xml") {
                excluded.add(it)
                return@onEnter false
            }
            val isGradle = configurationFile.name == "build.gradle" || configurationFile.name == "build.gradle.kts"
            if (it.isGradleBuildFolder() && isGradle) {
                excluded.add(it)
                return@onEnter false
            }
            return@onEnter true
        }.forEach { _ ->
            // noop, collects the sequence
        }
        return excluded
    }

    fun executeMavenCopyCommands(sourceFolder: File, buildLogBuilder: StringBuilder) = runMavenCopyCommands(sourceFolder, buildLogBuilder, LOG, project)

    private fun executeGradleBuildScript(sourceFolder: File, buildLogBuilder: StringBuilder) = runGradleScript(sourceFolder, buildLogBuilder, LOG, project)

    private fun getPythonExecutable(): String? {
        val pythonExecutables = listOf("python", "python3", "py", "py3")
        for (executable in pythonExecutables) {
            try {
                val commandLine = GeneralCommandLine(executable, "--version")
                val processOutput = ExecUtil.execAndGetOutput(commandLine)
                val exitCode = processOutput.exitCode
                if (exitCode == 0) {
                    return executable
                }
            } catch (e: Exception) {
                // Ignore errors and try another executable
            }
        }
        return null
    }

    // sourceFolder points to the directory containing the build file of the project
    private fun checkAndCreateGradleWrapper(sourceFolder: File): Boolean {
        val gradleWrapperFile = when {
            SystemInfo.isWindows -> File(sourceFolder, "gradlew.bat")
            else -> File(sourceFolder, "gradlew")
        }
        if (gradleWrapperFile.exists()) {
            return true
        }
        try {
            val commandLine = GeneralCommandLine("gradle", "wrapper").withWorkDirectory(sourceFolder)
            val processOutput = ExecUtil.execAndGetOutput(commandLine)
            if (processOutput.exitCode != 0) {
                return false
            }
            return gradleWrapperFile.exists()
        } catch (e: Exception) {
            return false
        }
    }

    private fun runGradleScript(sourceFolder: File, buildLogBuilder: StringBuilder, logger: Logger, project: Project): LocalBuildResult {
        val pythonExecutable = getPythonExecutable() ?: return LocalBuildResult.Failure("Python not found")
        val gradleWrapperExists = checkAndCreateGradleWrapper(sourceFolder)
        if (!gradleWrapperExists) {
            return LocalBuildResult.Failure("Gradle wrapper could not be created")
        }
        val scriptPath = "/pythonScript/gradle_copy_deps.py" // AwsToolkit.PLUGINS_INFO.getValue(AwsPlugin.Q).path?.resolve("codetransform/jetbrains-community/src/software/aws/toolkits/jetbrains/services/codemodernizer/utils/gradle_copy_deps.py")
        // TO-DO: evaluate if withWorkDirectory is needed here, and whether to use path or absolutePath
        val commandLine = GeneralCommandLine("$pythonExecutable $scriptPath ${sourceFolder.path}")
        try {
            val processOutput = ExecUtil.execAndGetOutput(commandLine)
            LOG.warn { "gradle_copy_deps.py finished with exitCode = ${processOutput.exitCode} and output = ${processOutput.stdout}\n${processOutput.stderr}" }
            if (processOutput.isCancelled) {
                return LocalBuildResult.Cancelled
            }
            if (processOutput.exitCode != 0) {
                return LocalBuildResult.Failure("gradle_copy_deps.py failed")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return LocalBuildResult.Failure("gradle_copy_deps.py failed")
        }
        // We don't use a separate dependency folder for Gradle apps
        return LocalBuildResult.Success(null)
    }

    private fun executeHilMavenCopyDependency(sourceFolder: File, destinationFolder: File, buildLogBuilder: StringBuilder) = runHilMavenCopyDependency(
        sourceFolder,
        destinationFolder,
        buildLogBuilder,
        LOG,
        project
    )

    fun copyHilDependencyUsingMaven(hilTepDirPath: Path): LocalBuildResult {
        val sourceFolder = File(getPathToHilArtifactPomFolder(hilTepDirPath).pathString)
        val destinationFolder = Files.createDirectories(getPathToHilDependenciesRootDir(hilTepDirPath)).toFile()
        val buildLogBuilder = StringBuilder("Starting Build Log...\n")
        return executeHilMavenCopyDependency(sourceFolder, destinationFolder, buildLogBuilder)
    }

    fun getDependencies(): LocalBuildResult {
        val root = configurationFile.parent
        val sourceFolder = File(root.path)
        val buildLogBuilder = StringBuilder("Starting Build Log...\n")
        val isGradle = configurationFile.name == "build.gradle" || configurationFile.name == "build.gradle.kts"
        return if (isGradle) executeGradleBuildScript(sourceFolder, buildLogBuilder) else executeMavenCopyCommands(sourceFolder, buildLogBuilder)
    }

    fun createDependencyReportUsingMaven(hilTempPomPath: Path): MavenDependencyReportCommandsResult {
        val sourceFolder = File(hilTempPomPath.pathString)
        val buildLogBuilder = StringBuilder("Starting Build Log...\n")
        return executeDependencyVersionReportUsingMaven(sourceFolder, buildLogBuilder)
    }
    private fun executeDependencyVersionReportUsingMaven(
        sourceFolder: File,
        buildLogBuilder: StringBuilder
    ) = runDependencyReportCommands(sourceFolder, buildLogBuilder, LOG, project)

    fun createZipForHilUpload(hilTempPath: Path, manifest: CodeTransformHilDownloadManifest?, targetVersion: String): ZipCreationResult =
        runReadAction {
            try {
                if (manifest == null) {
                    throw CodeModernizerException("No Hil manifest found")
                }

                val depRootPath = getPathToHilDependenciesRootDir(hilTempPath)
                val depDirectory = File(depRootPath.pathString)

                val dependencyFiles = iterateThroughDependencies(depDirectory)

                val depSources = File(HIL_DEPENDENCIES_ROOT_NAME)

                val file = Files.createFile(getPathToHilUploadZip(hilTempPath))
                ZipOutputStream(Files.newOutputStream(file)).use { zip ->
                    // 1) manifest.json
                    mapper.writeValueAsString(
                        CodeTransformHilUploadManifest(
                            hilInput = HilInput(
                                dependenciesRoot = "$HIL_DEPENDENCIES_ROOT_NAME/",
                                pomGroupId = manifest.pomGroupId,
                                pomArtifactId = manifest.pomArtifactId,
                                targetPomVersion = targetVersion,
                            )
                        )
                    )
                        .byteInputStream()
                        .use {
                            zip.putNextEntry(HIL_MANIFEST_FILE_NAME, it)
                        }

                    // 2) Dependencies
                    dependencyFiles.forEach { depFile ->
                        val relativePath = File(depFile.path).relativeTo(depDirectory)
                        val paddedPath = depSources.resolve(relativePath)
                        var paddedPathString = paddedPath.toPath().toString()
                        // Convert Windows file path to work on Linux
                        if (File.separatorChar != '/') {
                            paddedPathString = paddedPathString.replace('\\', '/')
                        }
                        depFile.inputStream().use {
                            zip.putNextEntry(paddedPathString, it)
                        }
                    }
                }

                ZipCreationResult.Succeeded(file.toFile())
            } catch (e: Exception) {
                LOG.error(e) { e.message.toString() }
                throw CodeModernizerException("Unknown exception occurred")
            }
        }

    fun createZipWithModuleFiles(localBuildResult: LocalBuildResult): ZipCreationResult {
        val telemetry = CodeTransformTelemetryManager.getInstance(project)
        val root = configurationFile.parent
        val sourceFolder = File(root.path)
        val buildLogBuilder = StringBuilder("Starting Build Log...\n")
        showTransformationHub()
        // TODO: deprecated metric - remove after BI starts using new metric
        telemetry.dependenciesCopied()
        val depDirectory = if (localBuildResult is LocalBuildResult.Success) {
            localBuildResult.dependencyDirectory // will be null for Gradle projects
        } else {
            // here, localBuildResult will always be Success so this should never happen
            null
        }

        return runReadAction {
            try {
                val directoriesToExclude = findDirectoriesToExclude(sourceFolder)
                val files = VfsUtil.collectChildrenRecursively(root).filter { child ->
                    val childPath = Path(child.path)
                    // TO-DO: make sure this is ignoring files correctly
                    !child.isDirectory && !childPath.isIgnoredFile() && directoriesToExclude.none { childPath.startsWith(it.toPath()) }
                }
                // separate directory for dependencies is only used for Maven projects
                val dependencyFiles = if (configurationFile.name == MAVEN_CONFIGURATION_FILE_NAME)
                    depDirectory?.let { iterateThroughDependencies(it) }
                else null

                val zipSources = File(ZIP_SOURCES_PATH)
                val depSources = File(ZIP_DEPENDENCIES_PATH)
                val outputFile = createTemporaryZipFile { zip ->
                    // 1) Manifest file
                    val dependenciesRoot = "$ZIP_DEPENDENCIES_PATH/${depDirectory?.name}"
                    // TO-DO: fix backend issue where this needs to be equalsIgnoreCase: https://code.amazon.com/packages/ElasticGumbyCodeGenAgent/blobs/e4002a60a410da75c55aad1279a314204919bd80/--/src/main/java/com/amazonaws/elasticgumby/codegen/build/inference/FileBasedBuildSystemInference.java#L62
                    // then the below "GRADLE" can just be "Gradle"
                    val buildTool = if (configurationFile.name == MAVEN_CONFIGURATION_FILE_NAME) "Maven" else "GRADLE"
                    mapper.writeValueAsString(ZipManifest(dependenciesRoot = dependenciesRoot, buildTool = buildTool))
                        .byteInputStream()
                        .use {
                            zip.putNextEntry(Path(MANIFEST_PATH).toString(), it)
                        }

                    // 2) Dependencies, only used for Maven projects, since for Gradle the dependencies are included in "Source code" below
                    dependencyFiles?.forEach { depFile ->
                        val relativePath = depDirectory?.let { File(depFile.path).relativeTo(it.parentFile) }
                        val paddedPath = relativePath?.let { depSources.resolve(it) }
                        var paddedPathString = paddedPath?.toPath().toString()
                        // Convert Windows file path to work on Linux
                        if (File.separatorChar != '/') {
                            paddedPathString = paddedPathString.replace('\\', '/')
                        }
                        depFile.inputStream().use {
                            zip.putNextEntry(paddedPathString, it)
                        }
                    }

                    LOG.info { "Dependency files size = ${dependencyFiles?.sumOf { it.length().toInt() }}" }

                    // 3) Source code
                    files.forEach { file ->
                        val relativePath = File(file.path).relativeTo(sourceFolder)
                        val paddedPath = zipSources.resolve(relativePath)
                        var paddedPathString = paddedPath.toPath().toString()
                        // Convert Windows file path to work on Linux
                        if (File.separatorChar != '/') {
                            paddedPathString = paddedPathString.replace('\\', '/')
                        }
                        try {
                            file.inputStream.use {
                                zip.putNextEntry(paddedPathString, it)
                            }
                        } catch (e: NoSuchFileException) {
                            // continue without failing
                            LOG.error { "NoSuchFileException likely due to a symlink, skipping file" }
                        }
                    }

                    LOG.info { "Source code files size = ${files.sumOf { it.length.toInt() }}" }

                    // 4) Build Log
                    buildLogBuilder.toString().byteInputStream().use {
                        zip.putNextEntry(Path(BUILD_LOG_PATH).toString(), it)
                    }
                }.toFile()
                ZipCreationResult.Succeeded(outputFile)
            } catch (e: NoSuchFileException) {
                throw CodeModernizerException("Source folder not found")
            } catch (e: Exception) {
                LOG.error(e) { e.message.toString() }
                throw CodeModernizerException("Unknown exception occurred")
            } finally {
                depDirectory?.deleteRecursively()
            }
        }
    }

    private fun Path.isIgnoredFile() = ignoredFileExtensions.any { this.toFile().name.endsWith(it, ignoreCase = true) }

    fun iterateThroughDependencies(depDirectory: File): MutableList<File> {
        val dependencyFiles = mutableListOf<File>()
        Files.walkFileTree(
            depDirectory.toPath(),
            setOf(FileVisitOption.FOLLOW_LINKS),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(path: Path, attrs: BasicFileAttributes?): FileVisitResult {
                    if (!path.isIgnoredFile()) {
                        dependencyFiles.add(path.toFile())
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path?, exc: IOException?): FileVisitResult =
                    FileVisitResult.CONTINUE
            }
        )
        return dependencyFiles
    }

    fun showTransformationHub() = runInEdt {
        val appModernizerBottomWindow = ToolWindowManager.getInstance(project).getToolWindow(CodeModernizerBottomToolWindowFactory.id)
            ?: error(message("codemodernizer.toolwindow.problems_window_not_found"))
        appModernizerBottomWindow.show()
        CodeModernizerBottomWindowPanelManager.getInstance(project).setJobStartingUI()
    }

    companion object {
        private val LOG = getLogger<CodeModernizerSessionContext>()
    }
}
