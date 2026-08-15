import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    kotlin("jvm") version "2.4.10"
    id("fabric-loom") version "1.18.0-alpha.16"
    id("maven-publish")
}

version = "1.1.1"
group = "com.huhobot"

base {
    archivesName.set("penguin-server-fabric")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

// Minecraft 26.x 起原版不再混淆，Yarn / 官方映射均已停止发布，但 Loom 仍要求
// mappings 配置非空。这里在配置阶段生成一个三命名空间的空 tiny v2 存根，
// 使 remapJar / remapSourcesJar 成为恒等变换。
// 放在 .gradle/ 下：该目录已被 .gitignore 忽略，且不会被 `clean` 任务删除。
val mappingsStub: File = rootDir.resolve(".gradle/mappings-stub.jar")
mappingsStub.parentFile.mkdirs()
ZipOutputStream(mappingsStub.outputStream().buffered()).use { zos ->
    zos.putNextEntry(ZipEntry("mappings/mappings.tiny"))
    zos.write("tiny\t2\t0\tofficial\tintermediary\tnamed\n".toByteArray())
    zos.closeEntry()
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    mappings(files(mappingsStub))
    implementation("net.fabricmc:fabric-loader:0.19.3")
    implementation("net.fabricmc.fabric-api:fabric-api:0.157.0+26.2")

    // Fabric Language Kotlin
    implementation("net.fabricmc:fabric-language-kotlin:1.13.13+kotlin.2.4.10")

    // Gson for config
    include(implementation("com.google.code.gson:gson:2.10.1")!!)
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
        options.release.set(25)
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
        }
    }

    jar {
        from("LICENSE") {
            rename { "${it}_${base.archivesName.get()}" }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25

    withSourcesJar()
}
