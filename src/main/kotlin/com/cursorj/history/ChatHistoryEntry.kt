package com.cursorj.history

import kotlinx.serialization.Serializable

@Serializable
data class ChatHistoryEntry(
    val sessionId: String,
    var title: String,
    val createdAt: Long,
    var lastActivityAt: Long,
)
