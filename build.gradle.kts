import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
}

group = "com.xoxoax"
version = "1.0.4"

repositories {
    mavenCentral()

    // 1. Offizielles PaperMC Repository
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }

    // 2. KORREKTE URL: CodeMC Public enthält alle fertigen PacketEvents Releases
    maven {
        name = "codemc"
        url = uri("https://repo.codemc.io/repository/maven-public/")
    }
}

data class McVersion(
    val name: String,
    val paperApi: String,
    val javaVersion: Int
)

val versions = listOf(
    McVersion(
        "1_20_6",
        "io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT",
        21
    ),
    McVersion(
        "1_21",
        "io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT",
        21
    )
)

versions.forEach { versionInfo ->
    val sourceSet = sourceSets.create(versionInfo.name)
    sourceSet.java.srcDir("src/common/java")

    // Eigene Konfiguration für Bibliotheken, die in die fertige JAR eingeschlossen werden sollen
    val shadeConfiguration = configurations.create("shade${versionInfo.name}") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

    dependencies {
        // Paper-API wird nur zum Kompilieren benötigt, ist NICHT in der fertigen JAR
        add(
            sourceSet.compileOnlyConfigurationName,
            versionInfo.paperApi
        )

        // PacketEvents wird in die "shade"-Konfiguration gepackt
        add(
            shadeConfiguration.name,
            "com.github.retrooper:packetevents-spigot:2.12.2"
        )
    }

    // Saubere Classpath-Erweiterung für Gradle 8
    configurations.named(sourceSet.compileClasspathConfigurationName).configure {
        extendsFrom(shadeConfiguration)
    }

    tasks.named<JavaCompile>(
        sourceSet.compileJavaTaskName
    ) {
        javaCompiler.set(
            javaToolchains.compilerFor {
                languageVersion.set(
                    JavaLanguageVersion.of(
                        versionInfo.javaVersion
                    )
                )
            }
        )
    }

    // Wir registrieren den ShadowJar-Task sauber über den expliziten Import oben
    tasks.register<ShadowJar>("jar${versionInfo.name}") {
        archiveBaseName.set("xoxo-AntiCheat")
        archiveVersion.set(project.version.toString())
        archiveClassifier.set(versionInfo.name)

        from(sourceSet.output)
        configurations = listOf(shadeConfiguration)

        minimize()

        // RELOCATION: Benennt PacketEvents intern um
        relocate("com.github.retrooper.packetevents", "com.xoxoax.anticheat.lib.packetevents.api")
        relocate("io.github.retrooper.packetevents", "com.xoxoax.anticheat.lib.packetevents.impl")

        dependsOn(
            tasks.named(
                sourceSet.classesTaskName
            )
        )
    }
}
