import java.io.File
import org.gradle.api.GradleException
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.tasks.SourceSetContainer
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

    // Put the IntelliJ Platform test-framework on the integrationTest runtime classpath so JUnit
    // discovery can resolve com.intellij.* types referenced by integration tests. Without this the
    // whole suite aborts during discovery (NoClassDefFoundError: com.intellij.openapi.Disposable).
    intellijPlatform {
        testFramework(
            TestFrameworkType.Platform,
            configurationName = integrationTestSourceSet.implementationConfigurationName,
        )
    }
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
                <li>Agent, Plan, Ask, and simulated Debug modes with native IntelliJ UI, including multiple-choice cards for agent questions</li>
                <li>Permission control with per-turn rollback via Local History</li>
            </ul>
            <p>Project home: <a href="$projectUrl">$projectUrl</a></p>
            <p>Support and issues: <a href="$issuesUrl">$issuesUrl</a></p>
        """.trimIndent()

        changeNotes = """
            <h3>${pluginVersion.get()}</h3>
            <ul>
                <li><strong>Debug mode (simulated):</strong> A fourth chat mode that reproduces Cursor's Debug workflow — hypothesize, add temporary instrumentation, reproduce, analyze runtime evidence, apply a targeted fix, then clean up. Because ACP exposes no native <code>debug</code> mode, CursorJ keeps the session in <code>agent</code>, injects a Debug workflow prompt, and captures runtime evidence via a per-session log file under <code>&lt;project&gt;/.cursorj/debug/</code> plus a localhost token-gated log sink. Post-turn actions offer <strong>I reproduced it</strong>, <strong>Clean up</strong>, and <strong>View logs</strong>.</li>
                <li><strong>Edit &amp; resend:</strong> Edit one of your earlier messages to branch the conversation from that point — later replies are discarded and the prior turns are replayed as context into a fresh session.</li>
                <li><strong>Fixed Windows drag-and-drop of <code>file://</code> paths:</strong> Dropping or pasting a <code>file://</code> URI that wraps a Windows path (e.g. the GNOME copied-files clipboard format) now resolves correctly instead of being dropped.</li>
                <li><strong>Faster IDE shutdown:</strong> Closing the IDE (or a project) no longer hangs several seconds per open CursorJ tab.</li>
            </ul>
            <p><strong>Known Cursor ACP limitations</strong> (agent/protocol constraints, not plugin bugs):</p>
            <ul>
                <li><strong>No native Debug session mode:</strong> the agent advertises only <code>agent</code>, <code>plan</code>, and <code>ask</code> and rejects a <code>debug</code> mode id, so Debug is simulated locally rather than driven by the agent.</li>
                <li><strong>No conversation forking:</strong> the agent does not advertise ACP <code>session/fork</code>, so Edit &amp; resend replays context into a new session instead of branching server-side.</li>
                <li><strong>Model parameter tiers cannot be switched at runtime:</strong> each model is offered with a single baked-in parameter set, so toggles such as Composer Normal/Fast or Claude Opus effort levels are not selectable through ACP.</li>
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

// Manual-only real-agent integration tests run through the plugin's `testIde` support so they get
// the full IntelliJ Platform bootstrap (sandbox, PathClassLoader, JUnit5 session listener) that a
// plain Gradle `Test` task cannot provide. The registration name becomes the `integrationTest` task.
val integrationTest by intellijPlatformTesting.testIde.registering {
    task {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Runs manual-only real agent CLI integration tests."
        // Append (don't replace) so the testIde-provided platform bootstrap classpath — which defines
        // com.intellij.util.lang.PathClassLoader used as the JVM system class loader — is preserved.
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath += integrationTestSourceSet.output + integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        shouldRunAfter("test")

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

gradle.taskGraph.whenReady {
    val isCi = providers.environmentVariable("CI").orNull?.let {
        it.equals("true", ignoreCase = true) || it == "1"
    } ?: false
    val requestedIntegration = allTasks.any { it.name == "integrationTest" }
    if (isCi && requestedIntegration) {
        throw GradleException("`integrationTest` is manual-only and must never run on CI.")
    }
}
