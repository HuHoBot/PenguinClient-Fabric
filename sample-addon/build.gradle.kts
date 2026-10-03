plugins {
    kotlin("jvm") version "2.3.21"
}

version = "1.0.0"
group = "example.huhobot"

// 跟随宿主：1.20.1 需 Java 21；编译 mc26.2 分支的 jar 时传 -PjavaRelease=25
val javaRelease = (findProperty("javaRelease") as String?)?.toInt() ?: 21

repositories {
    mavenCentral()
}

dependencies {
    // 宿主 API 以源码形式参与编译（见 src/main/kotlin/com/huhobot/penguin/addon/）。
    // 这样示例工程可以独立构建，不需要先编译模组本体或从 Releases 下载 jar。
    //
    // 发布自己的插件时，把真实模组 jar 传给宿主 API 即可：
    //   ./gradlew jar -PpenguinJar=/path/to/penguin-server-fabric-<版本>.jar
    providers.gradleProperty("penguinJar").orNull?.let { compileOnly(files(it)) }
    compileOnly(kotlin("stdlib"))
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(javaRelease)
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(
                when (javaRelease) {
                    25 -> org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
                    21 -> org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
                    17 -> org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
                    else -> throw GradleException("不支持的 javaRelease=$javaRelease")
                }
            )
        }
    }
    jar {
        archiveBaseName.set("example-addon")
        manifest {
            attributes(
                "Implementation-Title" to "ExampleAddon",
                "Implementation-Version" to project.version
            )
        }
        // 宿主 API 桩只用于编译，绝不能进 jar：
        // 插件 jar 里带 com.huhobot.penguin.* 的副本会让加载器扫到重复类，
        // 且这些副本由示例工程的类加载器加载，与宿主的同名类不是同一个 Class。
        exclude("com/huhobot/**")
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(javaRelease)
    targetCompatibility = sourceCompatibility
}
