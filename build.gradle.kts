plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.intellij.platform)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

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
        instrumentationTools()
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.cursorj"
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }

        vendor {
            name = "CursorJ"
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
        """.trimIndent()
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

    wrapper {
        gradleVersion = "8.13"
    }
}
