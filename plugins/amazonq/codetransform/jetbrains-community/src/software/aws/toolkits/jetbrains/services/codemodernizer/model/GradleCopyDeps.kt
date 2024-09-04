// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.model

import java.io.File
import java.io.FileWriter
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// this is a potential way to convert the Python script to a Gradle script, have NOT reviewed / tested it
val useOfflineDependency = """
final addDownloadedDependenciesRepository(rooted, receiver) {
  receiver.repositories.maven {
    url uri("${'$'}rooted.rootDir}/qct-gradle/configuration")
    metadataSources {
      mavenPom()
      artifact()
    }
  }
}
 
settingsEvaluated { settings ->
  addDownloadedDependenciesRepository settings, settings.buildscript
  addDownloadedDependenciesRepository settings, settings.pluginManagement
}
 
allprojects { project ->
  addDownloadedDependenciesRepository project, project.buildscript
  addDownloadedDependenciesRepository project, project
}
"""

val runBuildEnvCopyContent = """
import java.nio.file.Files
import java.nio.file.StandardCopyOption
 
gradle.rootProject {
    // Task to run buildEnvironment and capture its output
    task runAndParseBuildEnvironment {
        doLast {
            try {
                def buildEnvironmentOutput = new ByteArrayOutputStream()
                exec {
                    // Use the gradlew wrapper from the project's directory
                    commandLine "${'$'}project.projectDir}/gradlew", 'buildEnvironment'
                    standardOutput = buildEnvironmentOutput
                }

                def outputString = buildEnvironmentOutput.toString('UTF-8')
                def localM2Dir = new File(System.getProperty("user.home"), ".m2/repository")
                def gradleCacheDir = new File("${'$'}project.projectDir}/qct-gradle/START/caches/modules-2/files-2.1")
                def destinationDir = new File("${'$'}project.projectDir}/qct-gradle/configuration")

                // Helper method to copy files to m2 format
                def copyToM2 = { File file, String group, String name, String version ->
                    try {
                        def m2Path = "${'$'}{group.replace('.', '/')}/${'$'}name/${'$'}version"
                        def m2Dir = new File(destinationDir, m2Path)
                        m2Dir.mkdirs()
                        def m2File = new File(m2Dir, file.name)
                        println "this is the m2 path ${'$'}m2Path"
                        Files.copy(file.toPath(), m2File.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } catch (Exception e) {
                        println "Failed to copy file ${'$'}{file.name} to M2 format: ${'$'}{e.message}"
                    }
                }

                // Helper method to search and copy artifact in m2 directory
                def searchAndCopyArtifactInM2 = { String group, String name, String version ->
                    try {
                        def m2Path = "${'$'}{group.replace('.', '/')}/${'$'}name/${'$'}version"
                        def artifactDir = new File(localM2Dir, m2Path)
                        if (artifactDir.exists() && artifactDir.isDirectory()) {
                            println "Found artifact in local m2: ${'$'}{artifactDir.path}"
                            artifactDir.listFiles().each { file ->
                                try {
                                    println "  Copying File: ${'$'}{file.name}"
                                    copyToM2(file, group, name, version)
                                } catch (Exception e) {
                                    println "Error copying file ${'$'}{file.name}: ${'$'}{e.message}"
                                }
                            }
                            return true
                        }
                    } catch (Exception e) {
                        println "Error searching artifact in local m2: ${'$'}{e.message}"
                    }
                    return false
                }

                // Helper method to search and copy artifact in Gradle cache directory
                def searchAndCopyArtifactInGradleCache = { String group, String name, String version ->
                    try {
                        def cachePath = "${'$'}{group}/${'$'}name/${'$'}version"  // Path as is for Gradle cache
                        def artifactDir = new File(gradleCacheDir, cachePath)
                        if (artifactDir.exists() && artifactDir.isDirectory()) {
                            println "Found artifact in Gradle cache: ${'$'}{artifactDir.path}"
                            artifactDir.listFiles().each { file ->
                                try {
                                    println "  Copying File: ${'$'}{file.name}"
                                    // Change path to m2 structure
                                    copyToM2(file, group, name, version)
                                } catch (Exception e) {
                                    println "Error copying file ${'$'}{file.name}: ${'$'}{e.message}"
                                }
                            }
                            return true
                        }
                    } catch (Exception e) {
                        println "Error searching artifact in Gradle cache: ${'$'}{e.message}"
                    }
                    return false
                }

                // Helper method to search and copy artifact in local m2 or Gradle cache
                def searchAndCopyArtifact = { String group, String name, String version ->
                    try {
                        if (!searchAndCopyArtifactInM2(group, name, version)) {
                            if (!searchAndCopyArtifactInGradleCache(group, name, version)) {
                                println "Artifact not found: ${'$'}{group}:${'$'}{name}:${'$'}{version}"
                            }
                        }
                    } catch (Exception e) {
                        println "Error searching and copying artifact: ${'$'}{e.message}"
                    }
                }

                // Parse the buildEnvironment output
                println "=== Parsing buildEnvironment Output ==="
                def pattern = ~/(\S+:\S+:\S+)/
                outputString.eachLine { line ->
                    try {
                        def matcher = pattern.matcher(line)
                        if (matcher.find()) {
                            def artifact = matcher.group(1)
                            def (group, name, version) = artifact.split(':')
                            searchAndCopyArtifact(group, name, version)
                        }
                    } catch (Exception e) {
                        println "Error parsing line: ${'$'}line, ${'$'}{e.message}"
                    }
                }
            } catch (Exception e) {
                println "Error running buildEnvironment task: ${'$'}{e.message}"
            }
        }
    }
}
"""

val printContents = """

    import java.nio.file.Files
    import java.nio.file.Path
    import java.nio.file.StandardCopyOption
 
gradle.rootProject {
    task printResolvedDependenciesAndTransformToM2 {
        doLast {
            def destinationDir = new File("${'$'}project.projectDir}/qct-gradle/configuration")
    
            // Helper method to copy files to m2 format
            def copyToM2 = { File file, String group, String name, String version ->
                try {
                    def m2Path = "${'$'}{group.replace('.', '/')}/${'$'}name"
                    def m2Dir = new File(destinationDir, m2Path)
                    m2Dir.mkdirs()
                    def m2File = new File(m2Dir, file.name)
                    Files.copy(file.toPath(), m2File.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (Exception e) {
                    println "Failed to copy file ${'$'}{file.name} to M2 format: ${'$'}{e.message}"
                }
            }
    
            // Print buildscript configurations (plugins)
            println "=== Plugins ==="
            buildscript.configurations.each { config ->
                try {
                    if (config.canBeResolved) {
                        println "Configuration: ${'$'}{config.name}"
                        config.incoming.artifactView { viewConfig ->
                            viewConfig.lenient(true)
                        }.artifacts.each { artifact ->
                            def artifactPath = artifact.file.path
                            if (!artifactPath.startsWith(destinationDir.path)) {
                                try {
                                    println "  Transforming Dependency: ${'$'}{artifact.id.componentIdentifier.displayName}, File: ${'$'}{artifact.file}"
                                    def parts = artifact.id.componentIdentifier.displayName.split(':')
                                    if (parts.length == 3) {
                                        def (group, name, version) = parts
                                        copyToM2(artifact.file, group, name, version)
                                    } else {
                                        println "Unexpected format: ${'$'}{artifact.id.componentIdentifier.displayName}"
                                    }
                                } catch (Exception e) {
                                    println "Error processing artifact ${'$'}{artifact.file}: ${'$'}{e.message}"
                                }
                            }
                        }
                        println ""
                    } else {
                        println "Configuration: ${'$'}{config.name} cannot be resolved."
                        println ""
                    }
                } catch (Exception e) {
                    println "Error processing configuration ${'$'}{config.name}: ${'$'}{e.message}"
                }
            }
    
            // Print regular project dependencies
            println "=== Dependencies ==="
            configurations.each { config ->
                try {
                    if (config.canBeResolved) {
                        println "Configuration: ${'$'}{config.name}"
                        config.incoming.artifactView { viewConfig ->
                            viewConfig.lenient(true)
                        }.artifacts.each { artifact ->
                            def artifactPath = artifact.file.path
                            if (!artifactPath.startsWith(destinationDir.path)) {
                                try {
                                    println "  Transforming Dependency: ${'$'}{artifact.id.componentIdentifier.displayName}, File: ${'$'}{artifact.file}"
                                    def (group, name, version) = artifact.id.componentIdentifier.displayName.split(':')
                                    copyToM2(artifact.file, group, name, version)
                                } catch (Exception e) {
                                    println "Error processing artifact ${'$'}{artifact.file}: ${'$'}{e.message}"
                                }
                            }
                        }
                        println ""
                    } else {
                        println "Configuration: ${'$'}{config.name} cannot be resolved."
                        println ""
                    }
                } catch (Exception e) {
                    println "Error processing configuration ${'$'}{config.name}: ${'$'}{e.message}"
                }
            }
    
            // Resolve and print plugin marker artifacts
            println "=== Plugin Marker Artifacts ==="
            def pluginMarkerConfiguration = configurations.detachedConfiguration()
    
            // Access plugin dependencies from the buildscript block
            try {
                buildscript.configurations.classpath.resolvedConfiguration.firstLevelModuleDependencies.each { dependency ->
                    dependency.children.each { transitiveDependency ->
                        def pluginArtifact = "${'$'}{transitiveDependency.moduleGroup}:${'$'}{transitiveDependency.moduleName}:${'$'}{transitiveDependency.moduleVersion}"
                        pluginMarkerConfiguration.dependencies.add(dependencies.create(pluginArtifact))
                    }
                }
    
                pluginMarkerConfiguration.incoming.artifactView { viewConfig ->
                    viewConfig.lenient(true)
                }.artifacts.each { artifact ->
                    def artifactPath = artifact.file.path
                    if (!artifactPath.startsWith(destinationDir.path)) {
                        try {
                            println "  Transforming Plugin Marker: ${'$'}{artifact.id.componentIdentifier.displayName}, File: ${'$'}{artifact.file}"
                            def (group, name, version) = artifact.id.componentIdentifier.displayName.split(':')
                            copyToM2(artifact.file, group, name, version)
                        } catch (Exception e) {
                            println "Error processing plugin marker artifact ${'$'}{artifact.file}: ${'$'}{e.message}"
                        }
                    }
                }
            } catch (Exception e) {
                println "Error resolving plugin marker artifacts: ${'$'}{e.message}"
            }
        }
    }
}
"""

val copyModulesScriptContent = """
gradle.rootProject {
    ext.destDir = "${'$'}projectDir"
    ext.startDir = "${'$'}destDir/qct-gradle/START"
    ext.finalDir = "${'$'}destDir/qct-gradle/FINAL"
 
    task buildProject(type: Exec) {
        commandLine "${'$'}destDir/gradlew", "build", "-p", destDir, "-g", startDir
    }
 
    task copyModules2 {
        dependsOn buildProject
        doLast {
            def srcDir = file("${'$'}startDir/caches/modules-2/files-2.1/")
            def destDir = file("${'$'}finalDir/caches/modules-2/files-2.1/")
            
            if (srcDir.exists()) {
                copy {
                    from srcDir
                    into destDir
                }
                println "modules-2/files-2.1 folder copied successfully."
            } else {
                throw new GradleException("Failed to copy the modules-2/files-2.1 folder: source directory does not exist.")
            }
        }
    }
}
"""

val customInitScriptContent = """

gradle.rootProject {
    task cacheToMavenLocal(type: Sync) {
        def destinationDirectory = "${'$'}project.projectDir}/qct-gradle/configuration"
        println(destinationDirectory)
        
        from new File("${'$'}project.projectDir}/qct-gradle/START", "caches/modules-2/files-2.1")
        into destinationDirectory
        
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE // Choose the strategy that suits your needs
        
        eachFile {
            List<String> parts = it.path.split('/')
            println(parts)
            it.path = [parts[0].replace('.', '/'), parts[1], parts[2], parts[4]].join('/')
        }
        
        includeEmptyDirs false
    }

}
"""

val fullDownload = """
// This is the init.gradle script

def resolveConfiguration = { config ->
    try {
        config.resolvedConfiguration.resolvedArtifacts.each { artifact ->
            println "  - ${'$'}{artifact.file.absolutePath}"
        }
        config.resolvedConfiguration.firstLevelModuleDependencies.each { dep ->
            resolveDependencies(dep)
        }
    } catch (Exception e) {
        println "  Failed to resolve configuration: ${'$'}{config.name}, reason: ${'$'}{e.message}"
    }
}

def resolveDependencies = { dep ->
    dep.children.each { childDep ->
        childDep.moduleArtifacts.each { artifact ->
            println "  - ${'$'}{artifact.file.absolutePath}"
        }
        resolveDependencies(childDep)
    }
}

allprojects {
    afterEvaluate { project ->
        project.tasks.create("resolveAllConfigurations") {
            doLast {
                project.configurations.all { config ->
                    println "Resolving configuration: ${'$'}{config.name}"
                    resolveConfiguration(config)
                }
            }
        }

        project.tasks.create("resolveDetachedConfigurations") {
            doLast {
                def detachedConfigs = project.configurations.findAll { it.name.startsWith('detached') }
                detachedConfigs.each { config ->
                    println "Resolving detached configuration: ${'$'}{config.name}"
                    resolveConfiguration(config)
                }
            }
        }
    }
}

"""

fun runGradlew(projectDir: String) {
    try {
        val process = ProcessBuilder("./gradlew", "-p", projectDir)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        process.waitFor()
        println("gradlew command executed successfully.")
    } catch (e: Exception) {
        println("An error occurred while running gradlew: ${e.message}")
    }
}

class GradleWrapperPropertiesManager(private val projectDir: String) {
    private val propertiesFile = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
    private val originalValues = mutableMapOf<String, String>()

    private fun readProperties(): Map<String, String> {
        val properties = mutableMapOf<String, String>()
        propertiesFile.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if ("=" in line) {
                    val (key, value) = line.split("=", limit = 2)
                    properties[key] = value
                }
            }
        }
        return properties
    }

    private fun writeProperties(properties: Map<String, String>) {
        val writer = FileWriter(propertiesFile)
        properties.forEach { (key, value) ->
            writer.write("$key=$value\n")
        }
        writer.close()
    }

    fun setCustomDistribution(distributionBase: String, distributionPath: String) {
        val properties = readProperties().toMutableMap()
        originalValues["distributionBase"] = properties["distributionBase"] ?: ""
        originalValues["distributionPath"] = properties["distributionPath"] ?: ""

        properties["distributionBase"] = distributionBase
        properties["distributionPath"] = distributionPath
        properties["zipStoreBase"] = distributionBase
        properties["zipStorePath"] = distributionPath

        writeProperties(properties)
        println("Set custom distributionBase=$distributionBase and distributionPath=$distributionPath")
    }

    fun resetDistribution() {
        val properties = readProperties().toMutableMap()
        if ("distributionBase" in originalValues) {
            properties["distributionBase"] = originalValues["distributionBase"] ?: ""
        }
        if ("distributionPath" in originalValues) {
            properties["distributionPath"] = originalValues["distributionPath"] ?: ""
        }

        writeProperties(properties)
        println("Reset distributionBase and distributionPath to original values")
    }

    fun modifyInitScripts(distributionPath: String) {
        val distributionPathFull = File(projectDir, distributionPath)
        if (!distributionPathFull.exists()) {
            println("The distribution path ${distributionPathFull.path} does not exist.")
            return
        }

        distributionPathFull.walkTopDown().maxDepth(4).forEach { file ->
            if (file.isDirectory && file.name == "init.d") {
                file.listFiles()?.filter { it.extension == "gradle" }?.forEach { scriptFile ->
                    ScriptModifier(projectDir).modifyScript(scriptFile.absolutePath)
                }
            }
        }
    }

    fun zipDistribution(distributionPath: String, zipFileName: String) {
        val distributionDir = File(projectDir, distributionPath)
        if (!distributionDir.exists()) {
            println("The distribution directory ${distributionDir.path} does not exist.")
            return
        }

        println("Starting traversal of ${distributionDir.path}...")

        var parentDirToZip: File? = null

        distributionDir.walkTopDown().forEach { file ->
            if (file.isDirectory && file.name == "init.d" && file.parentFile?.name == "bin") {
                parentDirToZip = file.parentFile
                println("Found 'init.d' and 'bin' under: ${file.parent}")
            }
        }

        if (parentDirToZip == null) {
            println("Could not find a parent directory containing both 'init.d' and 'bin'.")
            return
        }

        val outputZip = File(File(projectDir, "gradle/wrapper"), zipFileName)
        val tempDir = File(projectDir, "temp_zip_dir")

        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }
        tempDir.mkdirs()

        val subdirName = parentDirToZip?.name
        File(tempDir, subdirName).mkdirs()
        parentDirToZip?.copyRecursively(File(tempDir, subdirName))

        ZipOutputStream(outputZip.outputStream().buffered()).use { zipOut ->
            tempDir.walkBottomUp().forEach { file ->
                if (file.isFile) {
                    val relativePath = file.relativeTo(tempDir)
                    val entry = ZipEntry(relativePath.path)
                    zipOut.putNextEntry(entry)
                    file.inputStream().use { zipOut.write(it.readBytes()) }
                    zipOut.closeEntry()
                }
            }
        }

        println("Zipped distribution directory ${parentDirToZip?.path} as subdir to ${outputZip.path}")
        tempDir.deleteRecursively()
        println("Cleaned up temporary directory.")
    }

    fun updateDistributionUrl(zipFileName: String) {
        val properties = readProperties().toMutableMap()
        properties["distributionUrl"] = zipFileName
        writeProperties(properties)
        println("Updated distributionUrl to point to $zipFileName")
    }

    fun checkUpdateUrlIsPublic(): Boolean {
        val pattern = Pattern.compile("services\\.gradle\\.org/distributions/gradle-[\\d\\.]+-(bin|all)\\.zip")
        val properties = readProperties()
        val distributionUrl = properties["distributionUrl"] ?: ""
        println("this is the distribution url, $distributionUrl")
        return if (pattern.matcher(distributionUrl).matches()) {
            println("Found a public gradle distribution URL")
            true
        } else {
            println("The distributionUrl does not match the expected pattern.")
            false
        }
    }
}

class ScriptModifier(private val projectDir: String) {
    fun modifyScript(scriptPath: String) {
        val localPath = "${File(projectDir).absolutePath}/qct-gradle/configuration"
        val scriptContent = File(scriptPath).readText()

        val modifiedContent = addMavenRepos(scriptContent, localPath)
        File(scriptPath).writeText(modifiedContent)

        println("Modified script at $scriptPath to use local path $localPath")
    }

    private fun addMavenRepos(content: String, localPath: String): String {
        val newMavenRepo = """
        maven {
            url "$localPath"
            metadataSources {
            mavenPom()
            artifact()
            }
        }
        """.trimIndent()

        var modifiedContent = ""
        var startIdx = 0
        while (true) {
            val startIdxOfRepo = content.indexOf("repositories {", startIdx)
            if (startIdxOfRepo == -1) {
                break
            }

            var openBraces = 1
            var endIdx = startIdxOfRepo + "repositories {".length
            while (openBraces > 0 && endIdx < content.length) {
                if (content[endIdx] == '{') {
                    openBraces++
                } else if (content[endIdx] == '}') {
                    openBraces--
                }
                endIdx++
            }

            val modifiedRepositoriesBlock = content.substring(startIdxOfRepo, endIdx - 1).trim() + "\n${newMavenRepo.trim()}\n" + content.substring(endIdx - 1, endIdx)

            modifiedContent += content.substring(startIdx, startIdxOfRepo) + modifiedRepositoriesBlock

            startIdx = endIdx
        }

        modifiedContent += content.substring(startIdx)

        return modifiedContent
    }
}

fun createInitScript(directory: String, initName: String, content: String): String {
    val qctGradleDir = File(directory, "qct-gradle")
    qctGradleDir.mkdirs()
    val filePath = File(qctGradleDir, initName).absolutePath
    File(filePath).writeText(content)
    println("init.gradle file created successfully at $filePath")
    return filePath
}

fun makeGradlewExecutable(gradlewPath: String) {
    try {
        ProcessBuilder("chmod", "+x", gradlewPath)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
            .waitFor()
        println("made gradlew executable at $gradlewPath")
    } catch (e: Exception) {
        println("Error making gradlew executable: ${e.message}")
        e.printStackTrace()
    }
}

fun runGradleTask(initScriptPath: String, directoryPath: String, task: String) {
    try {
        ProcessBuilder("$directoryPath/gradlew", task, "--init-script", initScriptPath, "-g", "$directoryPath/qct-gradle/START", "-p", directoryPath, "--info")
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
            .waitFor()
    } catch (e: Exception) {
        println("this was this task that failed: $task")
        println("Error running Gradle task: ${e.message}")
        e.printStackTrace()
    }
}

fun runOfflineBuild(initScriptPath: String, directoryPath: String) {
    try {
        ProcessBuilder("$directoryPath/gradlew", "build", "--init-script", initScriptPath, "-g", "$directoryPath/qct-gradle/FINAL", "-p", directoryPath, "--offline")
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
            .waitFor()
        println("run_offline_build() succeeded")
    } catch (e: Exception) {
        println("Error running offline build: ${e.message}")
        e.printStackTrace()
    }
}

fun createRunTask(path: String, initFileName: String, content: String, taskName: String) {
    val initScriptPath = createInitScript(path, initFileName, content)
    runGradleTask(initScriptPath, path, taskName)
}

fun run(sourceFolder: File) {
    val gradlewPath = File(sourceFolder, "gradlew").absolutePath
    if (File(gradlewPath).exists()) {
        println("gradlew executable found")
        makeGradlewExecutable(gradlewPath)
    } else {
        println("gradlew executable not found. Please ensure you have a Gradle wrapper at the root of your project. Run 'gradle wrapper' to generate one.")
        return
    }

    try {
        createRunTask(sourceFolder.path, "copyModules-init.gradle", copyModulesScriptContent, "copyModules2")
        createRunTask(sourceFolder.path, "custom-init.gradle", customInitScriptContent, "cacheToMavenLocal")
        createRunTask(sourceFolder.path, "resolved-paths-init.gradle", printContents, "printResolvedDependenciesAndTransformToM2")
        createRunTask(sourceFolder.path, "custom-init.gradle", customInitScriptContent, "cacheToMavenLocal")
        createRunTask(sourceFolder.path, "buildEnv-copy-init.gradle", runBuildEnvCopyContent, "runAndParseBuildEnvironment")
        val buildOfflineDependencies = createInitScript(sourceFolder.path, "use-downloaded-dependencies.gradle", useOfflineDependency)
        // runOfflineBuild(buildOfflineDependencies, sourceFolder.path)
    } catch (e: Exception) {
        println("An error occurred: ${e.message}")
        e.printStackTrace()
    }
}

fun runProperties(dir: String, action: String, distBase: String, distPath: String, zipName: String) {
    val projectDirectory = dir
    val manager = GradleWrapperPropertiesManager(projectDirectory)

    when (action) {
        "set" -> {
            if (!manager.checkUpdateUrlIsPublic()) {
                val distributionBase = distBase
                val distributionPath = distPath
                val zipFileName = zipName

                manager.setCustomDistribution(distributionBase, distributionPath)

                runGradlew(projectDirectory)

                manager.modifyInitScripts(distributionPath)

                manager.zipDistribution(distributionPath, zipFileName)

                manager.updateDistributionUrl(zipFileName)

                println("DONE")
            }
        }
        "reset" -> manager.resetDistribution()
        else -> println("Unknown action. Use 'set' to set custom distribution or 'reset' to reset to original values.")
    }
}

fun main(args: Array<String>) {
    if (args.size != 1) {
        println("Usage: <directory_path>")
        println("Expected 1 argument but got ${args.size} arguments: ${args.contentToString()}")
        return
    }

    val sourceFolder = File(args[0])
    val distBase = "PROJECT"
    val distPath = "custom-wrapper/dists"
    val zipPath = "customDist.zip"

    run(sourceFolder)
    runProperties(sourceFolder.path, "set", distBase, distPath, zipPath)
}
