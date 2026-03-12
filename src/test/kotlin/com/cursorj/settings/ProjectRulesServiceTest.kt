package com.cursorj.settings

import com.intellij.openapi.project.Project
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectRulesServiceTest {

    @Test
    fun `getRulesDirectory returns null when project has no base path`() {
        val project = projectWithBasePath(null)

        val result = ProjectRulesService.getRulesDirectory(project)

        assertNull(result)
    }

    @Test
    fun `getRulesDirectory returns null when rules dir does not exist`() {
        val workDir = Files.createTempDirectory("cursorj-rules-none").toFile()
        try {
            val project = projectWithBasePath(workDir.absolutePath)

            val result = ProjectRulesService.getRulesDirectory(project)

            assertNull(result)
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `listRuleFiles returns empty when no rules dir`() {
        val workDir = Files.createTempDirectory("cursorj-rules-list-empty").toFile()
        try {
            val project = projectWithBasePath(workDir.absolutePath)

            val result = ProjectRulesService.listRuleFiles(project)

            assertTrue(result.isEmpty())
        } finally {
            workDir.deleteRecursively()
        }
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
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }
        } as Project
    }
}
