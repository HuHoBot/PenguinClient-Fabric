plugins {
    kotlin("jvm") version "2.3.21"
    id("fabric-loom") version "1.7-SNAPSHOT"
    id("maven-publish")
}

version = "1.0.0"
group = "com.huhobot"

base {
    archivesName.set("penguin-client-fabric")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    // Minecraft & Fabric
    minecraft("com.mojang:minecraft:1.20.1")
    mappings("net.fabricmc:yarn:1.20.1+build.10:v2")
    modImplementation("net.fabricmc:fabric-loader:0.16.9")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.92.9+1.20.1")

    // Fabric Language Kotlin
    modImplementation("net.fabricmc:fabric-language-kotlin:1.13.11+kotlin.2.3.21")

    // Kotlin stdlib
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
}

tasks {
    processResources {
        inputs.property("version", project.version)

        filesMatching("fabric.mod.json") {
            expand("version" to project.version)
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "21"
        }
    }

    jar {
        from("LICENSE") {
            rename { "${it}_${base.archivesName.get()}" }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21

    withSourcesJar()
}
