package com.cursorj.settings

import com.intellij.openapi.vfs.VirtualFile

/** Where a skill was discovered on disk. */
enum class SkillSourceKind {
    CURSOR,
    AGENTS,
    CLAUDE,
    CODEX,
}

/** Project vs user-global skill directory. */
enum class SkillScope {
    PROJECT,
    GLOBAL,
}

/**
 * A discovered Cursor-compatible skill: a directory containing [SKILL.md] with optional YAML frontmatter.
 *
 * @param name Skill invocation name (the folder containing SKILL.md; frontmatter `name` is advisory).
 * @param description From frontmatter when present.
 * @param skillFile The SKILL.md virtual file when resolved in the VFS.
 * @param folder The directory containing SKILL.md (skill root folder).
 * @param nestedScopeDir For skills under e.g. `apps/web/.cursor/skills/`, the path prefix (`apps/web`) relative to project base; null for repo-root skills.
 * @param paths Glob patterns from frontmatter limiting activation.
 * @param disableModelInvocation When true, skill is manual-only (`/name`).
 * @param sourceKind Which `.cursor` / `.agents` / etc. tree this came from.
 */
data class SkillDefinition(
    val name: String,
    val description: String,
    val skillFile: VirtualFile,
    val folder: VirtualFile,
    val nestedScopeDir: String?,
    val paths: List<String>,
    val disableModelInvocation: Boolean,
    val sourceKind: SkillSourceKind,
    val scope: SkillScope,
)
