package com.cursorj.permissions

import com.cursorj.acp.messages.PermissionOption
import com.cursorj.acp.messages.RequestPermissionParams
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

object PermissionPolicy {
    private val readOnlyMethods = setOf(
        "fs/read_text_file",
        "fs/list_directory",
        "fs/get_file_info",
        "fs/search_files",
        "fs/find_text_in_files",
        "editor/get_open_files",
        "editor/find_symbol",
        "editor/list_file_symbols",
        "editor/find_references",
        "terminal/get_output",
        "terminal/wait",
        "terminal/kill",
        "terminal/release",
    )

    private val sensitiveMethods = setOf(
        "fs/write_text_file",
        "fs/create_directory",
        "terminal/create",
    )

    fun normalizePermissionKey(raw: String): String {
        val lowered = raw.trim().lowercase()
        if (lowered.isEmpty()) return ""
        return lowered
            .replace(Regex("[^a-z0-9:/._-]+"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
    }

    fun chooseAllowOption(options: List<PermissionOption>): String {
        val ids = options.map { it.optionId }
        ids.firstOrNull { normalizePermissionKey(it) == "allow-once" }?.let { return it }
        ids.firstOrNull { normalizePermissionKey(it) == "allow-always" }?.let { return it }
        ids.firstOrNull { normalizePermissionKey(it).startsWith("allow") }?.let { return it }
        return options.firstOrNull()?.optionId ?: "allow-once"
    }

    fun chooseRejectOption(options: List<PermissionOption>): String {
        val ids = options.map { it.optionId }
        ids.firstOrNull { normalizePermissionKey(it) == "reject-once" }?.let { return it }
        ids.firstOrNull { normalizePermissionKey(it).startsWith("reject") }?.let { return it }
        return "reject-once"
    }

    fun isAllowOption(optionId: String): Boolean = normalizePermissionKey(optionId).startsWith("allow")

    fun isReadOnlyMethod(method: String): Boolean = method in readOnlyMethods

    fun isSensitiveMethod(method: String): Boolean = method in sensitiveMethods

    fun approvedKeysForRequest(request: RequestPermissionParams): Set<String> {
        val keys = LinkedHashSet<String>()
        val toolName = resolvedToolIdentifier(request)
        val description = request.description.orEmpty()
        val toolKey = normalizePermissionKey(toolName)
        if (toolKey.isNotEmpty()) keys.add(toolKey)

        inferCategory(toolName, description, request.arguments)?.let { keys.add(it) }

        if (looksLikeShellTool(toolName, description)) {
            extractShellCommandBase(request.arguments)?.let { keys.add("shell:$it") }
        }
        return keys
    }

    fun displayToolName(request: RequestPermissionParams): String {
        val explicit = firstNonBlank(
            request.toolName,
            request.toolNameSnake,
            request.tool,
            request.name,
            request.method,
        )
        if (explicit != null) return prettifyToolName(explicit)

        val fromDescription = inferFromDescription(request.description)
        if (fromDescription != null) return prettifyToolName(fromDescription)

        if (extractShellCommandBase(request.arguments) != null) return "Shell"
        val inferred = inferCategory("", request.description.orEmpty(), request.arguments)
        return when (inferred) {
            "terminal/create" -> "Shell"
            "fs/write_text_file" -> "Write"
            "fs/read_text_file" -> "Read"
            else -> "Unknown tool"
        }
    }

    fun withResolvedToolName(request: RequestPermissionParams): RequestPermissionParams {
        if (!request.toolName.isNullOrBlank()) return request
        val resolved = displayToolName(request)
        if (resolved == "Unknown tool") return request
        return request.copy(toolName = resolved)
    }

    fun candidateKeysForMethod(method: String, params: JsonElement): Set<String> {
        val keys = LinkedHashSet<String>()
        val methodKey = normalizePermissionKey(method)
        if (methodKey.isNotEmpty()) keys.add(methodKey)
        when (method) {
            "fs/write_text_file", "fs/create_directory" -> {
                keys.add("fs/write_text_file")
                keys.add("write")
                keys.add("edit")
                keys.add("filesystem")
            }
            "terminal/create" -> {
                keys.add("terminal/create")
                keys.add("terminal")
                keys.add("shell")
                extractShellCommandBase(params)?.let { keys.add("shell:$it") }
            }
            "fs/read_text_file", "fs/list_directory", "fs/get_file_info", "fs/search_files", "fs/find_text_in_files",
            "editor/get_open_files", "editor/find_symbol", "editor/list_file_symbols", "editor/find_references" -> {
                keys.add("fs/read_text_file")
                keys.add("read")
                keys.add("search")
            }
        }
        return keys
    }

    fun shouldAutoAllowRequest(
        mode: PermissionMode,
        approvedKeys: Set<String>,
        request: RequestPermissionParams,
    ): String? {
        val allow = chooseAllowOption(request.options)
        if (mode == PermissionMode.RUN_EVERYTHING) return allow
        if (mode == PermissionMode.AUTO_APPROVE_SAFE && looksReadOnlyRequest(request)) return allow
        val normalizedApproved = approvedKeys.map { normalizePermissionKey(it) }.toSet()
        val requestKeys = approvedKeysForRequest(request).map { normalizePermissionKey(it) }
        if (requestKeys.any { it.isNotEmpty() && it in normalizedApproved }) return allow
        return null
    }

    fun shouldAllowMethodExecution(
        mode: PermissionMode,
        approvedKeys: Set<String>,
        method: String,
        params: JsonElement,
    ): Boolean {
        if (mode == PermissionMode.RUN_EVERYTHING) return true
        if (!isSensitiveMethod(method)) return true
        val normalizedApproved = approvedKeys.map { normalizePermissionKey(it) }.toSet()
        val candidates = candidateKeysForMethod(method, params).map { normalizePermissionKey(it) }
        return candidates.any { it.isNotEmpty() && it in normalizedApproved }
    }

    fun isPathInsideWorkspace(path: String, workspacePath: String?): Boolean {
        if (workspacePath.isNullOrBlank()) return true
        return try {
            val target = File(path).canonicalFile.toPath().normalize()
            val workspace = File(workspacePath).canonicalFile.toPath().normalize()
            target.startsWith(workspace)
        } catch (_: Exception) {
            false
        }
    }

    private fun looksLikeShellTool(toolName: String, description: String): Boolean {
        val text = "$toolName $description".lowercase()
        return listOf("shell", "terminal", "bash", "cmd", "powershell").any { it in text }
    }

    private fun looksReadOnlyRequest(request: RequestPermissionParams): Boolean {
        val text = "${request.toolName.orEmpty()} ${request.description.orEmpty()}".lowercase()
        return listOf("read", "search", "glob", "list", "inspect").any { it in text } &&
            listOf("write", "edit", "delete", "terminal", "shell", "command").none { it in text }
    }

    private fun inferCategory(toolName: String, description: String, args: JsonElement?): String? {
        val text = "$toolName $description".lowercase()
        val obj = args as? JsonObject
        return when {
            listOf("shell", "terminal", "command", "bash", "powershell").any { it in text } -> "terminal/create"
            listOf("write", "edit", "apply", "patch", "delete", "create directory", "mkdir").any { it in text } -> "fs/write_text_file"
            listOf("read", "search", "glob", "list").any { it in text } -> "fs/read_text_file"
            obj?.containsKey("command") == true -> "terminal/create"
            obj?.containsKey("content") == true -> "fs/write_text_file"
            obj?.containsKey("path") == true -> "fs/read_text_file"
            extractShellCommandBase(args) != null -> "terminal/create"
            else -> null
        }
    }

    private fun extractShellCommandBase(args: JsonElement?): String? {
        val obj = args as? JsonObject ?: return null
        val command = obj["command"]?.jsonPrimitive?.contentOrNull ?: return null
        return command
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.removePrefix("\"")
            ?.removePrefix("'")
            ?.lowercase()
    }

    private fun inferFromDescription(description: String?): String? {
        val desc = description?.trim().orEmpty()
        if (desc.isEmpty()) return null
        val lowered = desc.lowercase()
        return when {
            "terminal" in lowered || "shell" in lowered || "command" in lowered -> "Shell"
            "write" in lowered || "edit" in lowered || "apply" in lowered || "patch" in lowered -> "Write"
            "read" in lowered || "search" in lowered || "list" in lowered -> "Read"
            else -> null
        }
    }

    private fun prettifyToolName(raw: String): String {
        val value = raw.trim().ifEmpty { return "Unknown tool" }
        return when (normalizePermissionKey(value)) {
            "terminal/create", "shell", "terminal" -> "Shell"
            "fs/write_text_file", "write", "edit" -> "Write"
            "fs/read_text_file", "read", "search" -> "Read"
            else -> value
        }
    }

    private fun resolvedToolIdentifier(request: RequestPermissionParams): String {
        return firstNonBlank(
            request.toolName,
            request.toolNameSnake,
            request.tool,
            request.name,
            request.method,
            inferCategory("", request.description.orEmpty(), request.arguments),
        ).orEmpty()
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }
}
