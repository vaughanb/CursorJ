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
    implementation(libs.sqlite.jdbc)
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
            <p>CursorJ brings Cursor's AI agent into JetBrains IDEs via the
            <a href="https://agentclientprotocol.com/">Agent Client Protocol (ACP)</a>.</p>
            <ul>
                <li>Full agentic coding with file editing, terminal commands, and codebase understanding</li>
                <li>Project indexing with lexical search, symbol lookup, and optional semantic reranking</li>
                <li>Reliable SQLite-backed lexical persistence with safe concurrent index updates</li>
                <li>Active file and selection context injection with drag-and-drop file references</li>
                <li>Multiple concurrent chat sessions with intelligent tab naming</li>
                <li>Chat history with search and restore; prompt history</li>
                <li>Unified rules management for global user rules and project rules (<code>.cursor/rules/</code>)</li>
                <li>Agent, Plan, and Ask modes with native IntelliJ UI</li>
                <li>Permission control with per-turn rollback via Local History</li>
            </ul>
            <p>Project home: <a href="$projectUrl">$projectUrl</a></p>
            <p>Support and issues: <a href="$issuesUrl">$issuesUrl</a></p>
        """.trimIndent()

        changeNotes = """
            <h3>${pluginVersion.get()}</h3>
            <ul>
                <li>Consolidated global and project rules management into a single settings page in Settings &gt; Tools &gt; CursorJ</li>
                <li>Fixed SQLite index-store transaction-state errors by serializing concurrent write operations</li>
                <li>Added concurrency stress-test coverage for SQLite-backed indexing writes</li>
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

        doFirst {
            File(runIdeConfigDir).mkdirs()
            File(runIdeSystemDir).mkdirs()
            File(runIdeLogDir).mkdirs()
        }

        jvmArgs(
            "-Didea.config.path=$runIdeConfigDir",
            "-Didea.system.path=$runIdeSystemDir",
            "-Didea.log.path=$runIdeLogDir",
        )
    }

    wrapper {
        gradleVersion = "9.0.0"
    }
}
