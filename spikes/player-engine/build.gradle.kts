plugins {
    application
}

repositories {
    mavenCentral()
}

val javafxVersion = "27.0.1"
val osName = System.getProperty("os.name").lowercase()
val javafxPlatform = when {
    osName.contains("win") -> "win"
    osName.contains("mac") -> "mac"
    else -> "linux"
}

dependencies {
    for (module in listOf("base", "graphics", "controls", "media")) {
        implementation("org.openjfx:javafx-$module:$javafxVersion:$javafxPlatform")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "webpresenter.spike.Launcher"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    // Forward -Pargs="--auto --windowed" to the application.
    (project.findProperty("args") as String?)?.let { args(it.split(" ")) }
}
