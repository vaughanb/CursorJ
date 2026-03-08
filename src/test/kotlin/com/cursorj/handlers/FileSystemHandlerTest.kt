package com.cursorj.handlers

import com.intellij.openapi.project.Project
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemHandlerTest {
    @Test
    fun `resolve path uses project base path for relative paths`() {
        val baseDir = Files.createTempDirectory("cursorj-fs-base").toFile()
        try {
            val handler = FileSystemHandler(projectWithBasePath(baseDir.absolutePath))
            val resolved = invokePrivate<String>(handler, "resolvePath", "src/Main.kt")

            assertEquals(File(baseDir, "src/Main.kt").absolutePath, resolved)
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun `glob conversion supports wildcards and case insensitive matching`() {
        val handler = FileSystemHandler(projectWithBasePath(null))
        val regex = invokePrivate<Regex>(handler, "globToRegex", "*.kt")

        assertTrue(regex.matches("Main.kt"))
        assertTrue(regex.matches("main.KT"))
        assertFalse(regex.matches("Main.kt.bak"))
    }

    @Test
    fun `recursive search skips hidden and build-like directories`() {
        val root = Files.createTempDirectory("cursorj-fs-search").toFile()
        try {
            File(root, "visible.kt").writeText("ok")
            File(root, "src").mkdirs()
            File(root, "src/nested.kt").writeText("ok")
            File(root, ".hidden").mkdirs()
            File(root, ".hidden/hidden.kt").writeText("hidden")
            File(root, "node_modules").mkdirs()
            File(root, "node_modules/pkg.kt").writeText("ignored")
            File(root, "build").mkdirs()
            File(root, "build/generated.kt").writeText("ignored")
            File(root, "out").mkdirs()
            File(root, "out/output.kt").writeText("ignored")

            val handler = FileSystemHandler(projectWithBasePath(root.absolutePath))
            val regex = invokePrivate<Regex>(handler, "globToRegex", "*.kt")
            val results = mutableListOf<String>()

            invokePrivate<Unit>(handler, "searchFilesRecursive", root, regex, results, 100)

            val normalized = results.map { File(it).name }.sorted()
            assertEquals(listOf("nested.kt", "visible.kt"), normalized)
        } finally {
            root.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> invokePrivate(target: Any, methodName: String, vararg args: Any?): T {
        val method = target.javaClass.declaredMethods.first {
            it.name == methodName && it.parameterTypes.size == args.size
        }.apply { isAccessible = true }
        return method.invoke(target, *args) as T
    }

    private fun projectWithBasePath(basePath: String?): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePath
                "isDisposed" -> false
                "isDefault" -> false
                "getName" -> "test-project"
                "getLocationHash" -> "test-hash"
                else -> defaultValue(method.returnType)
            }
        } as Project
    }

    private fun defaultValue(returnType: Class<*>): Any? {
        return when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }
}
