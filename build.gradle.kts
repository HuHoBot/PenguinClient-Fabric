plugins {
    kotlin("jvm") version "2.3.21"
    id("fabric-loom") version "1.17-SNAPSHOT"
    id("maven-publish")
}

version = "1.1.7"
group = "com.huhobot"

base {
    archivesName.set("penguin-server-fabric")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://repo.codemc.io/repository/maven-public/")
}

dependencies {
    // Minecraft & Fabric（重定目标 1.21.11：1.21.11 删除数字权限等级，权限 API 见 PenguinCommand.kt）
    minecraft("com.mojang:minecraft:1.21.11")
    mappings("net.fabricmc:yarn:1.21.11+build.6:v2")
    modImplementation("net.fabricmc:fabric-loader:0.19.5")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.6+1.21.11")

    // Fabric Language Kotlin
    modImplementation("net.fabricmc:fabric-language-kotlin:1.13.11+kotlin.2.3.21")

    // Gson for config
    include(implementation("com.google.code.gson:gson:2.10.1")!!)

    // 扫码绑定：终端渲染二维码
    include(implementation("com.google.zxing:core:3.5.3")!!)

    // 测试：QQ 网关重连行为的回归用例（只连本地 HTTP 桩，不访问腾讯接口）
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    jar {
        from("LICENSE") {
            rename { "${it}_${base.archivesName.get()}" }
        }
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21

    withSourcesJar()
}
