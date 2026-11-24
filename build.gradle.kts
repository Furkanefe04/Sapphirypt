import java.security.MessageDigest
import java.util.HexFormat

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
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    launcher {
        name = "Sapphirypt"
    }
}
