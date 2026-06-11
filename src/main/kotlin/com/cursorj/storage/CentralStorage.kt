package com.cursorj.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Resolves per-workspace machine-local storage under [root]/projects/<sanitizedName>-<hash8>/ and
 * migrates legacy `<workspace>/.cursorj/` data into that directory once.
 */
class CentralStorage(
    private val root: File = defaultRoot(),
) {
    /**
     * Returns the central directory for [workspacePath], creating it and running a one-time legacy
     * migration from `<workspace>/.cursorj/` when needed. Returns null when [workspacePath] is null
     * or blank.
     */
    fun projectDir(workspacePath: String?): File? {
        val trimmed = workspacePath?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val workspaceRoot = canonicalWorkspaceRoot(trimmed)
        val key = projectKey(workspaceRoot)
        val dir = File(File(root, PROJECTS_SEGMENT), key)
        runCatching {
            Files.createDirectories(dir.toPath())
        }.onFailure { return null }

        val marker = File(dir, LEGACY_MIGRATION_MARKER)
        if (!marker.isFile) {
            runCatching { migrateLegacyData(workspaceRoot, dir) }
            runCatching { marker.writeText("", Charsets.UTF_8) }
        }
        return dir
    }

    /**
     * Computes the stable subdirectory name for a workspace root (for tests and diagnostics).
     */
    fun projectKeyForPath(workspacePath: String): String =
        projectKey(canonicalWorkspaceRoot(workspacePath.trim()))

    private fun canonicalWorkspaceRoot(workspacePath: String): File {
        val raw = File(workspacePath)
        return runCatching { raw.canonicalFile }.getOrElse { raw.absoluteFile }
    }

    private fun projectKey(workspaceRoot: File): String {
        val namePart = sanitizeDirName(workspaceRoot.name)
        val pathBytes = workspaceRoot.absolutePath.replace('\\', '/').toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(pathBytes)
        val hash8 = digest.take(4).joinToString("") { b -> "%02x".format(b) }
        return "$namePart-$hash8"
    }

    private fun migrateLegacyData(workspaceRoot: File, projectDir: File) {
        val legacy = File(workspaceRoot, LEGACY_RELATIVE)
        if (!legacy.isDirectory) return

        val legacyIndex = File(legacy, "index")
        val targetIndex = File(projectDir, "index")
        if (legacyIndex.isDirectory) {
            val targetEmpty = !targetIndex.exists() || targetIndex.listFiles()?.isEmpty() == true
            if (targetEmpty) {
                runCatching {
                    if (targetIndex.exists()) {
                        targetIndex.delete()
                    }
                    tryMovePath(legacyIndex.toPath(), targetIndex.toPath())
                }
            }
        }

        for (fileName in LEGACY_JSON_FILES) {
            val src = File(legacy, fileName)
            val dst = File(projectDir, fileName)
            if (src.isFile && !dst.exists()) {
                runCatching {
                    Files.createDirectories(dst.parentFile?.toPath() ?: projectDir.toPath())
                    tryMovePath(src.toPath(), dst.toPath())
                }
            }
        }

        runCatching {
            if (!legacy.isDirectory) return@runCatching
            val remaining = legacy.listFiles()?.filter { it.exists() } ?: return@runCatching
            if (remaining.isEmpty()) {
                legacy.delete()
            }
        }
    }

    private fun tryMovePath(source: Path, target: Path) {
        runCatching {
            val parent = target.parent ?: return@runCatching
            Files.createDirectories(parent)
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }.recoverCatching {
            if (Files.isDirectory(source)) {
                copyDirectoryRecursively(source.toFile(), target.toFile())
                deleteRecursively(source.toFile())
            } else {
                Files.createDirectories(target.parent)
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                Files.deleteIfExists(source)
            }
        }
    }

    private fun copyDirectoryRecursively(source: File, target: File) {
        if (!source.isDirectory) return
        target.mkdirs()
        val children = source.listFiles() ?: return
        for (child in children) {
            val destChild = File(target, child.name)
            if (child.isDirectory) {
                copyDirectoryRecursively(child, destChild)
            } else {
                runCatching {
                    Files.copy(child.toPath(), destChild.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun deleteRecursively(dir: File) {
        if (!dir.exists()) return
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { deleteRecursively(it) }
        }
        runCatching { dir.delete() }
    }

    companion object {
        private const val LEGACY_MIGRATION_MARKER = ".legacy_cursorj_migrated"
        private const val LEGACY_RELATIVE = ".cursorj"
        private const val PROJECTS_SEGMENT = "projects"

        internal val LEGACY_JSON_FILES = listOf(
            "chat-history-index-v1.json",
            "chat-transcripts-v1.json",
            "prompt-history-v1.json",
        )

        fun defaultRoot(): File {
            val home = System.getProperty("user.home")?.trim()?.takeIf { it.isNotBlank() }
                ?: return File(".cursorj")
            return File(home, ".cursorj")
        }

        fun sanitizeDirName(raw: String): String {
            val cleaned = raw
                .trim()
                .replace(Regex("""[\u0000-\u001f\\:*?"<>|]"""), "_")
                .trim('.')
                .ifBlank { "project" }
            return cleaned.take(64)
        }
    }
}
