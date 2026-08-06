package com.cursorj.settings

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.Path
import kotlin.io.walkTopDown

/**
 * Discovers and manages Cursor-compatible agent skills (`SKILL.md` under standard skill roots).
 * The Cursor `agent acp` CLI loads skills from these locations; this service mirrors discovery for UI and context.
 */
object SkillsService {
    private val log = Logger.getInstance(SkillsService::class.java)

    private val parentDirNames = setOf(".cursor", ".agents", ".claude", ".codex")

    private val skipDirectoryNames = setOf(
        ".git", "node_modules", ".gradle", "build", "dist", "out", "target",
        ".idea", ".svn", "__pycache__", ".venv", "venv",
    )

    /**
     * Discovers skills from standard project and global locations; project definitions override global for the same skill name.
     */
    fun discoverSkills(project: Project): List<SkillDefinition> {
        val basePath = project.basePath?.replace('\\', '/')?.trimEnd('/')
        val globalSkills = discoverGlobalSkills()
        val projectSkills = if (basePath.isNullOrBlank()) {
            emptyList()
        } else {
            discoverProjectSkills(File(basePath))
        }
        // Project skills shadow global on same invocation name (case-insensitive).
        val byName = linkedMapOf<String, SkillDefinition>()
        for (s in globalSkills) {
            byName[s.name.lowercase()] = s
        }
        for (s in projectSkills) {
            byName[s.name.lowercase()] = s
        }
        return byName.values.sortedWith(compareBy({ it.scope.ordinal }, { it.name.lowercase() }))
    }

    private fun discoverGlobalSkills(): List<SkillDefinition> {
        val homePath = System.getProperty("user.home") ?: return emptyList()
        val home = File(homePath)
        if (!home.isDirectory) return emptyList()
        val roots = parentDirNames.map { p -> File(home, "$p/skills") }.filter { it.isDirectory }
        return collectDefinitionsFromRoots(roots, home, SkillScope.GLOBAL)
    }

    private fun discoverProjectSkills(root: File): List<SkillDefinition> {
        if (!root.isDirectory) return emptyList()
        val roots = linkedSetOf<File>()
        collectSkillRoots(root.toPath(), roots, maxDepth = 40)
        return collectDefinitionsFromRoots(roots.toList(), root, SkillScope.PROJECT)
    }

    private fun collectDefinitionsFromRoots(
        roots: List<File>,
        anchorRoot: File,
        scope: SkillScope,
    ): List<SkillDefinition> {
        val out = mutableListOf<SkillDefinition>()
        val seenFile = mutableSetOf<String>()
        for (dir in roots) {
            walkSkillMdFiles(dir) { skillMd ->
                val key = runCatching { skillMd.canonicalFile.absolutePath.replace('\\', '/').lowercase() }
                    .getOrElse { skillMd.absolutePath.replace('\\', '/').lowercase() }
                if (!seenFile.add(key)) return@walkSkillMdFiles
                val def = buildDefinition(skillMd, anchorRoot, scope) ?: return@walkSkillMdFiles
                out.add(def)
            }
        }
        return out
    }

    private fun collectSkillRoots(
        start: Path,
        out: MutableSet<File>,
        maxDepth: Int,
        depth: Int = 0,
    ) {
        if (depth > maxDepth) return
        val f = start.toFile()
        if (!f.isDirectory) return
        if (f.name in skipDirectoryNames && depth > 0) return

        if (isSkillContainerDir(f)) {
            out.add(f)
        }

        val children = f.listFiles() ?: return
        for (c in children) {
            if (!c.isDirectory) continue
            if (c.name in skipDirectoryNames) continue
            collectSkillRoots(c.toPath(), out, maxDepth, depth + 1)
        }
    }

    private fun isSkillContainerDir(dir: File): Boolean {
        if (!dir.isDirectory || dir.name != "skills") return false
        val parent = dir.parentFile ?: return false
        return parent.name in parentDirNames
    }

    private fun walkSkillMdFiles(skillsRoot: File, onFile: (File) -> Unit) {
        if (!skillsRoot.isDirectory) return
        skillsRoot.walkTopDown()
            .maxDepth(15)
            .filter { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
            .forEach { onFile(it) }
    }

    private fun buildDefinition(skillMd: File, projectOrHomeRoot: File, scope: SkillScope): SkillDefinition? {
        val folder = skillMd.parentFile ?: return null
        val folderName = folder.name.ifBlank { return null }
        val sourceKind = sourceKindFromPath(folder)
        val nestedScopeDir = computeNestedScopeDir(folder, projectOrHomeRoot)

        val content = runCatching { skillMd.readText(Charsets.UTF_8) }.getOrElse { e ->
            log.warn("Failed to read skill file: ${skillMd.path}", e)
            return null
        }
        val fm = SkillFrontmatterParser.parse(content)
        val name = when {
            fm.name.isNullOrBlank() -> folderName
            fm.name != folderName -> {
                log.info("Skill frontmatter name '${fm.name}' != folder '$folderName'; using folder name for ${skillMd.path}")
                folderName
            }
            else -> fm.name
        }
        val description = fm.description.orEmpty()

        return SkillDefinition(
            name = name,
            description = description,
            skillFilePath = skillMd.absolutePath,
            folderPath = folder.absolutePath,
            nestedScopeDir = nestedScopeDir,
            paths = fm.paths,
            disableModelInvocation = fm.disableModelInvocation,
            sourceKind = sourceKind,
            scope = scope,
        )
    }

    private fun sourceKindFromPath(folder: File): SkillSourceKind {
        var p: File? = folder.parentFile
        while (p != null) {
            when (p.name) {
                ".cursor" -> return SkillSourceKind.CURSOR
                ".agents" -> return SkillSourceKind.AGENTS
                ".claude" -> return SkillSourceKind.CLAUDE
                ".codex" -> return SkillSourceKind.CODEX
            }
            p = p.parentFile
        }
        return SkillSourceKind.CURSOR
    }

    /**
     * For `apps/web/.cursor/skills/foo`, returns `apps/web` relative to [projectOrHomeRoot].
     * For repo-root `.cursor/skills/foo`, returns null.
     */
    private fun computeNestedScopeDir(folder: File, projectOrHomeRoot: File): String? {
        val rootCanon = runCatching { projectOrHomeRoot.canonicalFile }.getOrNull() ?: projectOrHomeRoot
        val skillsParent = folder.parentFile?.parentFile ?: return null
        if (skillsParent.name !in parentDirNames) return null
        val anchor = skillsParent.parentFile ?: return null
        val anchorCanon = runCatching { anchor.canonicalFile }.getOrNull() ?: anchor
        if (anchorCanon == rootCanon) return null
        val rel = try {
            rootCanon.toPath().relativize(anchorCanon.toPath()).toString().replace('\\', '/')
        } catch (_: Exception) {
            return null
        }
        return rel.takeIf { it.isNotBlank() && it != "." }
    }

    /**
     * Creates `.cursor/skills/<name>/SKILL.md` under the project with a valid scaffold.
     * @return the created file or null on failure / collision.
     */
    fun createSkill(project: Project, name: String): File? {
        val basePath = project.basePath ?: return null
        val sanitized = sanitizeSkillFolderName(name)
        val skillsDir = File(File(basePath, ".cursor"), "skills")
        if (!skillsDir.exists() && !skillsDir.mkdirs()) {
            log.warn("Failed to mkdirs: ${skillsDir.absolutePath}")
            return null
        }
        val skillFolder = File(skillsDir, sanitized)
        if (skillFolder.exists()) {
            log.warn("Skill folder already exists: ${skillFolder.absolutePath}")
            return null
        }
        if (!skillFolder.mkdirs()) {
            log.warn("Failed to create skill folder: ${skillFolder.absolutePath}")
            return null
        }
        val skillMd = File(skillFolder, "SKILL.md")
        val content = buildSkillScaffold(sanitized)
        return try {
            skillMd.writeText(content, Charsets.UTF_8)
            skillMd
        } catch (e: Exception) {
            log.warn("Failed to create SKILL.md", e)
            runCatching { skillFolder.deleteRecursively() }
            null
        }
    }

    private fun buildSkillScaffold(folderName: String): String = """
        ---
        name: $folderName
        description: "When to use this skill (be specific)."
        disable-model-invocation: false
        ---

        # $folderName

        Instructions for the agent go here.
    """.trimIndent()

    fun readSkillContent(file: VirtualFile): String {
        return runCatching { file.inputStream.readBytes().toString(Charsets.UTF_8) }.getOrElse { "" }
    }

    /**
     * Deletes the skill folder containing [definition]'s SKILL.md if it lies under an allowed skills root.
     */
    fun deleteSkill(project: Project, definition: SkillDefinition): Boolean {
        val basePath = project.basePath ?: return false
        val folderPath = definition.folderPath.replace('\\', '/')
        if (!isUnderProjectSkillRoots(basePath, folderPath)) {
            log.warn("Refusing to delete skill outside allowed project skill dirs: $folderPath")
            return false
        }
        val ioFolder = File(definition.folderPath)
        return try {
            ioFolder.deleteRecursively()
        } catch (e: Exception) {
            log.warn("Failed to delete skill folder", e)
            false
        }
    }

    private fun isUnderProjectSkillRoots(basePath: String, folderPath: String): Boolean {
        val normBase = basePath.replace('\\', '/').trimEnd('/')
        val normFolder = folderPath.replace('\\', '/').trimEnd('/')
        if (!normFolder.startsWith(normBase)) return false
        val rel = normFolder.removePrefix(normBase).trimStart('/')
        return parentDirNames.any { parent ->
            rel.contains("/$parent/skills/") || rel.startsWith("$parent/skills/")
        }
    }

    fun ensureProjectCursorSkillsDir(project: Project): VirtualFile? {
        val basePath = project.basePath ?: return null
        val dir = File(File(basePath, ".cursor"), "skills")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return VfsUtil.findFile(dir.toPath(), true)
    }

    private fun sanitizeSkillFolderName(name: String): String {
        return name
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifBlank { "skill" }
            .take(64)
    }
}
