package com.cursorj.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Manages project rules in `.cursor/rules/` (Cursor-compatible rule files).
 * Rules are .md or .mdc files; the agent CLI discovers them automatically.
 */
object ProjectRulesService {
    private val log = Logger.getInstance(ProjectRulesService::class.java)
    private const val RULES_DIR = ".cursor/rules"

    fun getRulesDirectory(project: Project): VirtualFile? {
        val basePath = project.basePath ?: return null
        val dir = File(basePath, RULES_DIR)
        if (!dir.exists()) return null
        return VfsUtil.findFile(dir.toPath(), true)
    }

    fun listRuleFiles(project: Project): List<VirtualFile> {
        val dir = getRulesDirectory(project) ?: return emptyList()
        val children = dir.children ?: return emptyList()
        return children
            .filter { it.name.endsWith(".md", ignoreCase = true) || it.name.endsWith(".mdc", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
    }

    fun createRuleFile(project: Project, name: String, content: String): VirtualFile? {
        val basePath = project.basePath ?: return null
        val rulesDir = File(basePath, RULES_DIR)
        if (!rulesDir.exists()) {
            rulesDir.mkdirs()
        }
        val sanitizedName = sanitizeFileName(name)
        val fileName = if (sanitizedName.endsWith(".md", ignoreCase = true) || sanitizedName.endsWith(".mdc", ignoreCase = true)) {
            sanitizedName
        } else {
            "$sanitizedName.mdc"
        }
        val file = File(rulesDir, fileName)
        if (file.exists()) {
            log.warn("Rule file already exists: ${file.absolutePath}")
            return null
        }
        return runWriteAction {
            try {
                file.writeText(content, Charsets.UTF_8)
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath.replace('\\', '/'))
            } catch (e: Exception) {
                log.warn("Failed to create rule file", e)
                null
            }
        }
    }

    fun readRuleContent(file: VirtualFile): String {
        return runCatching { file.inputStream.readBytes().toString(Charsets.UTF_8) }.getOrElse { "" }
    }

    fun writeRuleContent(file: VirtualFile, content: String) {
        ApplicationManager.getApplication().runWriteAction {
            file.setBinaryContent(content.toByteArray(Charsets.UTF_8))
        }
    }

    fun deleteRuleFile(project: Project, file: VirtualFile): Boolean {
        if (!file.path.replace('\\', '/').contains("/$RULES_DIR/")) {
            log.warn("Refusing to delete file outside rules directory: ${file.path}")
            return false
        }
        return runWriteAction {
            try {
                file.delete(project)
                true
            } catch (e: Exception) {
                log.warn("Failed to delete rule file", e)
                false
            }
        }
    }

    fun ensureRulesDirectory(project: Project): VirtualFile? {
        val basePath = project.basePath ?: return null
        val rulesDir = File(basePath, RULES_DIR)
        if (!rulesDir.exists()) {
            rulesDir.mkdirs()
        }
        return VfsUtil.findFile(rulesDir.toPath(), true)
    }

    private fun sanitizeFileName(name: String): String {
        return name
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "rule" }
    }
}
