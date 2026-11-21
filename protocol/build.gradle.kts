// Contract shared by the central server and the MediaPlayer (WebSocket messages and their JSON codec).
plugins {
    id("webpresenter.java-conventions")
    `java-library`
}

dependencies {
    api(platform(libs.jackson.bom))
    api("tools.jackson.core:jackson-databind")

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
