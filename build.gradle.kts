import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import org.gradle.internal.os.OperatingSystem

plugins {
    java
    application
    id("org.javamodularity.moduleplugin") version "1.8.12"
    id("org.openjfx.javafxplugin") version "0.0.13"
    id("org.beryx.jlink") version "2.25.0"
}

group = "com.sapphircrypt"
version = "0.1.0"

repositories {
    mavenCentral()
}

val junitVersion = "5.10.2"
val bouncyCastleVersion = "1.79"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(22)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

application {
    mainModule.set("com.sapphircrypt.sapphirypt")
    mainClass.set("com.sapphircrypt.sapphirypt.SapphiryptApplication")
}

javafx {
    version = "22.0.1"
    modules = listOf("javafx.controls", "javafx.fxml")
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:${bouncyCastleVersion}")

    testImplementation("org.junit.jupiter:junit-jupiter-api:${junitVersion}")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${junitVersion}")
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

tasks.withType<JavaExec>().configureEach {
    if (name.endsWith(".main()")) {
        modularity.inferModulePath.set(true)
        mainModule.set(application.mainModule)
        javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
        dependsOn(tasks.jar)
        doFirst {
            val jarFile = tasks.jar.get().archiveFile.get().asFile
            val classesDirs = sourceSets.main.get().output.classesDirs.files
            val resourcesDir = sourceSets.main.get().output.resourcesDir
            val classifier = javafx.platform.classifier
            val filteredFiles = classpath.files.filter { file ->
                file !in classesDirs &&
                    file != resourcesDir &&
                    !(file.name.startsWith("javafx-") && !file.name.contains("-$classifier.jar"))
            }
            classpath = files(jarFile, filteredFiles)
            if (jvmArgs.orEmpty().none { it == "--add-modules" }) {
                jvmArgs("--add-modules", javafx.modules.joinToString(","))
            }
        }
    }
}

tasks.register("sha256Checksums") {
    group = "distribution"
    description = "Generates SHA-256 checksum files for build artifacts."
    dependsOn(tasks.assemble)
    doLast {
        val dirs = listOf(
            layout.buildDirectory.dir("libs").get().asFile,
            layout.buildDirectory.dir("distributions").get().asFile
        )
        val hex = HexFormat.of()
        for (dir in dirs) {
            if (!dir.exists()) continue
            dir.listFiles { file ->
                file.isFile && !file.name.endsWith(".sha256")
            }?.forEach { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read = input.read(buffer)
                    while (read > 0) {
                        digest.update(buffer, 0, read)
                        read = input.read(buffer)
                    }
                }
                val hash = hex.formatHex(digest.digest())
                val outFile = file.resolveSibling("${file.name}.sha256")
                outFile.writeText("$hash  ${file.name}\n", Charsets.UTF_8)
            }
        }
    }
}

jlink {
    imageZip.set(layout.buildDirectory.file("/distributions/app-${javafx.platform.classifier}.zip"))
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages", "--ignore-signing-information"))
    launcher {
        name = "Sapphirypt"
    }
}

val currentOs = OperatingSystem.current()
val jpackageModulesDir = layout.buildDirectory.dir("jpackage-modules")
val windowsAppDestDir = layout.buildDirectory.dir("distributions/windows")
val windowsInstallerDestDir = layout.buildDirectory.dir("distributions/windows-installer")
val linuxAppDestDir = layout.buildDirectory.dir("distributions/linux")
val linuxDebDestDir = layout.buildDirectory.dir("distributions/linux-deb")
val linuxRpmDestDir = layout.buildDirectory.dir("distributions/linux-rpm")
val windowsIconFile = layout.projectDirectory.file("src/main/resources/com/sapphircrypt/sapphirypt/icons/sapphirypt.ico")
val linuxIconFile = layout.projectDirectory.file("src/main/resources/com/sapphircrypt/sapphirypt/icons/sapphirypt-logo-512.png")
val fileAssociationsFile = layout.projectDirectory.file("packaging/sapphirypt-file-associations.properties")

fun jpackageExecutable(): String {
    val jdkHome = javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath.asFile
    val exeName = if (currentOs.isWindows) "jpackage.exe" else "jpackage"
    return jdkHome.resolve("bin").resolve(exeName).absolutePath
}

fun baseJpackageArgs(type: String, destDir: File, iconFile: File): MutableList<String> = mutableListOf(
    "--type", type,
    "--name", "Sapphirypt",
    "--app-version", project.version.toString(),
    "--vendor", "Sapphirypt",
    "--description", "Sapphirypt Encrypted Vault",
    "--module-path", jpackageModulesDir.get().asFile.absolutePath,
    "--module", "${application.mainModule.get()}/${application.mainClass.get()}",
    "--add-modules", "jdk.localedata",
    "--java-options", "-Dfile.encoding=UTF-8",
    "--jlink-options", "--strip-native-commands --strip-debug --no-man-pages --no-header-files --ignore-signing-information",
    "--icon", iconFile.absolutePath,
    "--dest", destDir.absolutePath
)

val prepareJpackageModules = tasks.register<Sync>("prepareJpackageModules") {
    group = "distribution"
    description = "Collects modular application and runtime dependency JARs for jpackage."
    dependsOn(tasks.jar)
    from(tasks.jar.flatMap { it.archiveFile })
    from(configurations.runtimeClasspath.map { cp ->
        val classifier = javafx.platform.classifier
        cp.files.filter { file ->
            !(file.name.startsWith("javafx-") && !file.name.contains("-$classifier.jar"))
        }
    })
    into(jpackageModulesDir)
}

fun registerJpackageTask(
    taskName: String,
    taskDescription: String,
    requiredOsCheck: () -> Boolean,
    osErrorMessage: String,
    packageType: String,
    destDirProvider: Provider<Directory>,
    iconFile: RegularFile,
    extraArgs: List<String> = emptyList()
) = tasks.register<Exec>(taskName) {
    group = "distribution"
    description = taskDescription
    dependsOn(prepareJpackageModules)
    doFirst {
        if (!requiredOsCheck()) {
            throw GradleException(osErrorMessage)
        }
        val destDir = destDirProvider.get().asFile
        destDir.deleteRecursively()
        destDir.mkdirs()
        executable = jpackageExecutable()
        args = baseJpackageArgs(packageType, destDir, iconFile.asFile) + extraArgs
    }
}

val packageWindowsExe = registerJpackageTask(
    taskName = "packageWindowsExe",
    taskDescription = "Builds runnable Windows Sapphirypt.exe bundle under build/distributions/windows/Sapphirypt/.",
    requiredOsCheck = { currentOs.isWindows },
    osErrorMessage = "packageWindowsExe must be run on Windows because jpackage does not cross-compile .exe packages.",
    packageType = "app-image",
    destDirProvider = windowsAppDestDir,
    iconFile = windowsIconFile
)

registerJpackageTask(
    taskName = "packageWindowsInstaller",
    taskDescription = "Builds Windows .exe installer under build/distributions/windows-installer/ (requires WiX Toolset 3.x).",
    requiredOsCheck = { currentOs.isWindows },
    osErrorMessage = "packageWindowsInstaller must be run on Windows with WiX Toolset 3.x installed.",
    packageType = "exe",
    destDirProvider = windowsInstallerDestDir,
    iconFile = windowsIconFile,
    extraArgs = listOf(
        "--win-dir-chooser",
        "--win-menu",
        "--win-shortcut",
        "--win-shortcut-prompt",
        "--win-per-user-install",
        "--file-associations", fileAssociationsFile.asFile.absolutePath
    )
)

val packageLinuxApp = registerJpackageTask(
    taskName = "packageLinuxApp",
    taskDescription = "Builds runnable Linux Sapphirypt bundle under build/distributions/linux/Sapphirypt/bin/Sapphirypt.",
    requiredOsCheck = { currentOs.isLinux },
    osErrorMessage = "packageLinuxApp must be run on Linux or WSL with JDK 22 because jpackage builds native packages per host OS.",
    packageType = "app-image",
    destDirProvider = linuxAppDestDir,
    iconFile = linuxIconFile
)

val linuxInstallerExtraArgs = listOf(
    "--linux-shortcut",
    "--linux-menu-group", "Utility;Security",
    "--linux-package-name", "sapphirypt",
    "--file-associations", fileAssociationsFile.asFile.absolutePath
)

val packageLinuxDeb = registerJpackageTask(
    taskName = "packageLinuxDeb",
    taskDescription = "Builds Linux .deb package under build/distributions/linux-deb/.",
    requiredOsCheck = { currentOs.isLinux },
    osErrorMessage = "packageLinuxDeb must be run on Linux or WSL with JDK 22 and dpkg-deb installed.",
    packageType = "deb",
    destDirProvider = linuxDebDestDir,
    iconFile = linuxIconFile,
    extraArgs = linuxInstallerExtraArgs
)

registerJpackageTask(
    taskName = "packageLinuxRpm",
    taskDescription = "Builds Linux .rpm package under build/distributions/linux-rpm/.",
    requiredOsCheck = { currentOs.isLinux },
    osErrorMessage = "packageLinuxRpm must be run on Linux with JDK 22 and rpmbuild installed.",
    packageType = "rpm",
    destDirProvider = linuxRpmDestDir,
    iconFile = linuxIconFile,
    extraArgs = linuxInstallerExtraArgs
)

tasks.register("packageLinux") {
    group = "distribution"
    description = "Builds runnable Linux app bundle and .deb package (must run on Linux/WSL)."
    dependsOn(packageLinuxApp, packageLinuxDeb)
}

tasks.register("packageApp") {
    group = "distribution"
    description = "Builds the native runnable executable package for the current OS (Windows .exe or Linux executable)."
    if (currentOs.isWindows) {
        dependsOn(packageWindowsExe)
    } else if (currentOs.isLinux) {
        dependsOn(packageLinuxApp)
    }
}
