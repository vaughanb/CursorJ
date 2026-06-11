package com.cursorj.settings

import com.intellij.openapi.project.Project
import com.intellij.testFramework.ApplicationRule
import org.junit.After
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

@RunWith(JUnit4::class)
class SkillsServiceTest {

    companion object {
        @JvmField
        @ClassRule
        val appRule: ApplicationRule = ApplicationRule()
    }

    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanupTempDirs() {
        for (d in tempDirs) {
            runCatching { d.deleteRecursively() }
        }
        tempDirs.clear()
    }

    private fun newTempBase(prefix: String): File {
        val d = Files.createTempDirectory(prefix).toFile()
        tempDirs.add(d)
        return d
    }

    @Test
    fun discoverSkillsFindsRepoRootCursorSkill() {
        val base = newTempBase("cursorj-skills-root")
        val skillDir = File(File(File(base, ".cursor"), "skills"), "hello-skill")
        check(skillDir.mkdirs())
        File(skillDir, "SKILL.md").writeText(
            """
            ---
            name: hello-skill
            description: Hello
            ---
            # Hi
            """.trimIndent(),
        )
        val project = projectWithBasePath(base.absolutePath)
        val skills = SkillsService.discoverSkills(project)
        val hello = skills.singleOrNull { it.name == "hello-skill" }
        assertNotNull(hello)
        assertEquals("Hello", hello!!.description)
        assertEquals(SkillScope.PROJECT, hello.scope)
        assertNull(hello.nestedScopeDir)
    }

    @Test
    fun discoverSkillsNestedMonorepoPath() {
        val base = newTempBase("cursorj-skills-nested")
        val skillDir = File(File(File(File(base, "apps"), "web"), ".cursor"), "skills")
            .resolve("deploy")
        check(skillDir.mkdirs())
        File(skillDir, "SKILL.md").writeText(
            """
            ---
            name: deploy
            description: Deploy web
            ---
            """.trimIndent(),
        )
        val project = projectWithBasePath(base.absolutePath)
        val skills = SkillsService.discoverSkills(project)
        val deploy = skills.singleOrNull { it.name == "deploy" }
        assertNotNull(deploy)
        assertEquals("apps/web", deploy!!.nestedScopeDir)
    }

    @Test
    fun projectSkillShadowsGlobalByName() {
        val fakeHome = newTempBase("cursorj-skills-fake-home")
        val oldHome = System.getProperty("user.home")
        System.setProperty("user.home", fakeHome.absolutePath)
        try {
            val gDir = File(File(fakeHome, ".cursor"), "skills").resolve("dup")
            check(gDir.mkdirs())
            File(gDir, "SKILL.md").writeText(
                """
                ---
                name: dup
                description: global
                ---
                """.trimIndent(),
            )

            val base = newTempBase("cursorj-skills-proj")
            val pDir = File(File(base, ".cursor"), "skills").resolve("dup")
            check(pDir.mkdirs())
            File(pDir, "SKILL.md").writeText(
                """
                ---
                name: dup
                description: project
                ---
                """.trimIndent(),
            )
            val project = projectWithBasePath(base.absolutePath)
            val skills = SkillsService.discoverSkills(project)
            val dup = skills.single { it.name == "dup" }
            assertEquals("project", dup.description)
            assertEquals(SkillScope.PROJECT, dup.scope)
        } finally {
            System.setProperty("user.home", oldHome)
        }
    }

    @Test
    fun createSkillAndDeleteSkill() {
        val base = newTempBase("cursorj-skills-crud")
        val project = projectWithBasePath(base.absolutePath)
        val created = SkillsService.createSkill(project, "My Cool Skill")
        assertNotNull(created)
        assertTrue(created!!.path.replace('\\', '/').endsWith(".cursor/skills/my-cool-skill/SKILL.md"))
        var skills = SkillsService.discoverSkills(project)
        assertNotNull(skills.find { it.name == "my-cool-skill" })
        val def = skills.first { it.name == "my-cool-skill" }
        assertTrue(SkillsService.deleteSkill(project, def))
        skills = SkillsService.discoverSkills(project)
        assertNull(skills.find { it.name == "my-cool-skill" })
    }

    @Test
    fun deleteSkillRefusesPathOutsideProjectSkillRoots() {
        val base = newTempBase("cursorj-skills-guard")
        val project = projectWithBasePath(base.absolutePath)
        val outside = File(base, "not-skills/evil/SKILL.md")
        check(outside.parentFile.mkdirs())
        outside.writeText("---\nname: evil\n---\n")
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(outside.absolutePath.replace('\\', '/'))!!
        val folder = vf.parent!!
        val fake = SkillDefinition(
            name = "evil",
            description = "",
            skillFile = vf,
            folder = folder,
            nestedScopeDir = null,
            paths = emptyList(),
            disableModelInvocation = false,
            sourceKind = SkillSourceKind.CURSOR,
            scope = SkillScope.PROJECT,
        )
        assertTrue(!SkillsService.deleteSkill(project, fake))
    }

    private fun projectWithBasePath(basePath: String?): Project {
        return java.lang.reflect.Proxy.newProxyInstance(
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
