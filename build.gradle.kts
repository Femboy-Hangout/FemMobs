plugins {
    kotlin("jvm") version "2.3.20"
}

group = "dev.femmobs"
version = "1.1.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(kotlin("stdlib"))
    compileOnly("io.papermc.paper:paper-api:26.3.build.230-stable")
}

kotlin { jvmToolchain(25) }

tasks.processResources {
    filesMatching("paper-plugin.yml") { expand("version" to project.version) }
}

tasks.jar {
    from(configurations.runtimeClasspath.map { files -> files.map { if (it.isDirectory) it else zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
