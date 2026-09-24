plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
}

group = "top.cheesesmp.duelcore"
version = "1.0.0"
description = "Competitive 1v1 duels, ranked queues and tiers for Paper"

repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.128-stable")
    compileOnly("me.clip:placeholderapi:2.11.6")
    // JDBC drivers are bundled with the Paper server (sqlite-jdbc, mysql-connector-j); only needed for unit tests here.
    implementation("com.zaxxer:HikariCP:6.3.0") {
        exclude(group = "org.slf4j")
    }

    testImplementation("io.papermc.paper:paper-api:26.2.build.128-stable")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.xerial:sqlite-jdbc:3.50.3.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked", "-Xlint:removal"))
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

tasks.shadowJar {
    archiveBaseName.set("DuelCore")
    archiveClassifier.set("")
    relocate("com.zaxxer.hikari", "top.cheesesmp.duelcore.lib.hikari")
}

tasks.jar { enabled = false }
tasks.build { dependsOn(tasks.shadowJar) }

tasks.test {
    useJUnitPlatform()
}

tasks.register("printCp") {
    doLast { println(configurations.compileClasspath.get().asPath) }
}
