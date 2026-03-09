import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.intellij.platform)
}

val pluginVersion = providers.gradleProperty("pluginVersion")
val projectUrl = "https://github.com/vaughanb/CursorJ"
val issuesUrl = "https://github.com/vaughanb/CursorJ/issues"

group = providers.gradleProperty("pluginGroup").get()
version = pluginVersion.get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testRuntimeOnly("junit:junit:4.13.2")

    intellijPlatform {
        val platformType = providers.gradleProperty("platformType")
        val platformVersion = providers.gradleProperty("platformVersion")
        create(platformType, platformVersion)

        bundledPlugin("org.jetbrains.plugins.terminal")

        pluginVerifier()
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.cursorj"
        name = providers.gradleProperty("pluginName")
        version = pluginVersion

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }

        vendor {
            name = "CursorJ"
            url = projectUrl
        }

        description = """
            <p>CursorJ brings Cursor's AI agent into JetBrains IDEs via the Agent Client Protocol (ACP).</p>
            <ul>
                <li>Full agentic coding with file editing, terminal commands, and codebase understanding</li>
                <li>Active file and selection context injection</li>
                <li>Drag-and-drop file references</li>
                <li>Multiple concurrent chat sessions</li>
                <li>Native IntelliJ UI with syntax-highlighted code blocks and diff rendering</li>
            </ul>
            <p>Project home: <a href="$projectUrl">$projectUrl</a></p>
            <p>Support and issues: <a href="$issuesUrl">$issuesUrl</a></p>
        """.trimIndent()

        // Keep synchronized with CHANGELOG.md for each release.
        changeNotes = """
            <p>Initial public release of CursorJ.</p>
            <ul>
                <li>Agentic coding via Cursor ACP (files, terminal, and codebase context)</li>
                <li>Active file/selection context, drag-and-drop references, and multi-session chat</li>
                <li>Native permission dialogs and per-turn Local History rollback</li>
                <li>API keys stored in JetBrains Password Safe</li>
            </ul>
        """.trimIndent()
    }

    val publishingChannel = providers.gradleProperty("intellijPlatformPublishingChannel")
        .orElse(providers.environmentVariable("JETBRAINS_MARKETPLACE_CHANNEL"))
        .orElse("default")

    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
            .orElse(providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN"))
        channels = publishingChannel.map { listOf(it) }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    runIde {
        val runIdeSandboxRoot = layout.projectDirectory.dir(".runIde-sandbox").asFile
        val runIdeConfigDir = File(runIdeSandboxRoot, "config").absolutePath
        val runIdeSystemDir = File(runIdeSandboxRoot, "system").absolutePath
        val runIdeLogDir = File(runIdeSandboxRoot, "log").absolutePath

        // Keep interactive runIde state isolated from verification/search tasks.
        jvmArgs(
            "-Didea.config.path=$runIdeConfigDir",
            "-Didea.system.path=$runIdeSystemDir",
            "-Didea.log.path=$runIdeLogDir",
        )
    }

    wrapper {
        gradleVersion = "8.13"
    }
}
