plugins {
    id("webpresenter.java-conventions")
    application
}

val javafxVersion: String = libs.versions.javafx.get()
val osName = System.getProperty("os.name").lowercase()
val javafxPlatform = when {
    osName.contains("win") -> "win"
    osName.contains("mac") -> "mac"
    else -> "linux"
}

dependencies {
    implementation(project(":protocol"))
    for (module in listOf("base", "graphics", "controls")) {
        implementation("org.openjfx:javafx-$module:$javafxVersion:$javafxPlatform")
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "webpresenter.player.Launcher"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<JavaCompile>().configureEach {
    // The mpv bindings use the FFM API's restricted methods on purpose (native access is enabled at runtime).
    options.compilerArgs.add("-Xlint:-restricted")
}

// Create the Windows installer on a Windows host with:
//   .\gradlew.bat :player:windowsInstaller -PmpvDir=C:\path\to\mpv\runtime
// mpvDir must contain libmpv-2.dll (or mpv-2.dll) and all DLL dependencies.
val windowsInstaller by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a self-contained Windows installer for the MediaPlayer."
    onlyIf { System.getProperty("os.name").lowercase().contains("win") }
    dependsOn(tasks.installDist)

    val mpvDir = providers.gradleProperty("mpvDir").orNull
    val appLib = layout.buildDirectory.dir("install/player/lib")
    doFirst {
        val mpvPath = mpvDir ?: throw GradleException("Pass -PmpvDir=<folder containing libmpv and its DLL dependencies>")
        val source = file(mpvPath)
        if (!source.isDirectory || source.listFiles { f -> f.name.lowercase() in setOf("libmpv-2.dll", "mpv-2.dll", "mpv-1.dll") }
                .isNullOrEmpty()) {
            throw GradleException("mpvDir must contain libmpv-2.dll, mpv-2.dll, or mpv-1.dll: $source")
        }
        copy {
            from(source)
            include("*.dll")
            into(appLib)
        }
        val output = layout.buildDirectory.dir("windows-installer").get().asFile
        output.mkdirs()
        commandLine(
            "jpackage",
            "--type", "exe",
            "--name", "WebPresenter MediaPlayer",
            "--app-version", project.version.toString().takeUnless { it == "unspecified" }?.removeSuffix("-SNAPSHOT") ?: "1.0.0",
            "--input", appLib.get().asFile.absolutePath,
            "--main-jar", "player-${project.version}.jar",
            "--main-class", "webpresenter.player.Launcher",
            "--java-options", "--enable-native-access=ALL-UNNAMED",
            "--win-menu",
            "--win-shortcut",
            "--win-dir-chooser",
            "--dest", output.absolutePath
        )
    }
}
