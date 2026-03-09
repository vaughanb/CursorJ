package com.cursorj.permissions

enum class PermissionMode(val id: String) {
    ASK_EVERY_TIME("ask-every-time"),
    AUTO_APPROVE_SAFE("auto-approve-safe"),
    RUN_EVERYTHING("run-everything"),
    ;

    companion object {
        fun fromId(raw: String?): PermissionMode {
            return when (raw?.trim()?.lowercase()) {
                RUN_EVERYTHING.id, "allow", "run-everything" -> RUN_EVERYTHING
                AUTO_APPROVE_SAFE.id, "auto-approve-safe" -> AUTO_APPROVE_SAFE
                ASK_EVERY_TIME.id, "ask", "deny", null, "" -> ASK_EVERY_TIME
                else -> ASK_EVERY_TIME
            }
        }
    }
}
