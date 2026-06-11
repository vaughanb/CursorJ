import java.io.File
import org.gradle.api.GradleException
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.language.base.plugins.LifecycleBasePlugin

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
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")

    intellijPlatform {
        val platformType = providers.gradleProperty("platformType")
        val platformVersion = providers.gradleProperty("platformVersion")
        create(platformType, platformVersion)

        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(TestFrameworkType.Platform)

        pluginVerifier()
    }
}

val sourceSets = the<SourceSetContainer>()
val integrationTestSourceSet = sourceSets.create("integrationTest") {
    kotlin.srcDir("src/integrationTest/kotlin")
    resources.srcDir("src/integrationTest/resources")
    compileClasspath += sourceSets["test"].compileClasspath + sourceSets["test"].output
    runtimeClasspath += sourceSets["test"].runtimeClasspath + output
}

configurations[integrationTestSourceSet.implementationConfigurationName].extendsFrom(configurations["testImplementation"])
configurations[integrationTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    add(integrationTestSourceSet.implementationConfigurationName, kotlin("test"))
}

intellijPlatform {
    // Avoid :instrumentCode (Ant/Javac2): Apache Ant requires JAVA_HOME/Packages for Microsoft JDKs,
    // which that distribution does not ship — see https://github.com/microsoft/openjdk/issues/339.
    // This project has no UI Designer .form files; UI is Kotlin-only.
    instrumentCode = false

    pluginConfiguration {
        id = "com.cursorj"
        name = providers.gradleProperty("pluginName")
        version = pluginVersion

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Open-ended: omit until-build so the plugin is offered on future IDE versions (see SDK build-number-ranges doc).
            untilBuild = provider { null }
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
                <li>Inline <code>@file</code> references and image attachments via drag-and-drop or paste</li>
                <li>Active file and selection context injection</li>
                <li>Multiple concurrent chat sessions with intelligent tab naming</li>
                <li>Chat history with search and restore; prompt history</li>
                <li>Unified rules management for global user rules and project rules (<code>.cursor/rules/</code>)</li>
                <li>Cursor Skills discovery and settings management; inline <code>/</code> and <code>@</code> completion in chat (merged with ACP slash commands) that keeps focus in the input</li>
                <li>Agent, Plan, and Ask modes with native IntelliJ UI, including multiple-choice cards for agent questions</li>
                <li>Permission control with per-turn rollback via Local History</li>
            </ul>
            <p>Project home: <a href="$projectUrl">$projectUrl</a></p>
            <p>Support and issues: <a href="$issuesUrl">$issuesUrl</a></p>
        """.trimIndent()

        changeNotes = """
            <h3>${pluginVersion.get()}</h3>
            <ul>
                <li><strong>Cursor Skills:</strong> Discover <code>SKILL.md</code> skills, manage them in settings, and use inline <code>/</code> and <code>@</code> completion in chat (ACP <code>available_commands_update</code> merged in) that keeps focus in the input so you can keep typing to filter.</li>
                <li><strong>Interactive agent questions:</strong> Answer structured <code>cursor/ask_question</code> prompts and plan-mode multiple-choice questions using native chat cards.</li>
                <li><strong>Inline file &amp; image attachments:</strong> Drag-and-drop or paste project files as Cursor-style <code>@file</code> references, or attach images as removable chips sent to the agent.</li>
                <li><strong>Workspace indexing performance:</strong> SQLite WAL read/write separation, startup throttling, and debounced VFS updates reduce IDE hangs during indexing.</li>
                <li><strong>Custom index exclusions:</strong> Exclude folders and files from indexing with glob patterns in <strong>Settings &gt; Tools &gt; CursorJ</strong>.</li>
                <li><strong>Subagent task UI:</strong> Collapsible background task list for <code>cursor/task</code> with status, duration, and tooltips.</li>
                <li><strong>Token &amp; cost tracking:</strong> Per-message token breakdown and session usage bar (toggle in settings).</li>
                <li><strong>Richer chat markdown:</strong> Tables, task lists, nested quotes, strikethrough, autolinks, emoji aliases, and theme-aware colors.</li>
                <li><strong>Stability &amp; model control:</strong> Hardened ACP session stop logic, in-place model switching, and ACP-confirmed status reporting.</li>
                <li><strong>Plan mode fixes:</strong> Build button and on-disk plan sync when the agent edits plans under <code>.cursor/plans</code>.</li>
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

    val integrationTest by registering(Test::class) {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Runs manual-only real agent CLI integration tests."
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        shouldRunAfter(test)

        val integrationOptIn = providers.environmentVariable("CURSORJ_INTEGRATION")
        onlyIf {
            integrationOptIn.orNull == "1"
        }

        doFirst {
            val isCi = providers.environmentVariable("CI").orNull?.let {
                it.equals("true", ignoreCase = true) || it == "1"
            } ?: false
            if (isCi) {
                throw GradleException("`integrationTest` is manual-only and must never run on CI.")
            }
        }
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

gradle.taskGraph.whenReady {
    val isCi = providers.environmentVariable("CI").orNull?.let {
        it.equals("true", ignoreCase = true) || it == "1"
    } ?: false
    val requestedIntegration = allTasks.any { it.name == "integrationTest" }
    if (isCi && requestedIntegration) {
        throw GradleException("`integrationTest` is manual-only and must never run on CI.")
    }
}
