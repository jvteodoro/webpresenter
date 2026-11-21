// Shared configuration for every Java project in the monorepo.
plugins {
    java
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
