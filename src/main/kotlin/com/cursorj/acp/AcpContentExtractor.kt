package com.cursorj.acp

import kotlinx.serialization.json.*

object AcpContentExtractor {

    fun extractTextFromContent(contentElement: JsonElement?): String? {
        if (contentElement == null || contentElement is JsonNull) return null

        if (contentElement is JsonObject) {
            contentElement["text"]?.jsonPrimitive?.contentOrNull?.let { return it }
            val inner = contentElement["content"]
            if (inner is JsonObject) {
                return inner["text"]?.jsonPrimitive?.contentOrNull
            }
        }

        if (contentElement is JsonArray) {
            val sb = StringBuilder()
            for (block in contentElement) {
                if (block is JsonObject) {
                    val text = block["text"]?.jsonPrimitive?.contentOrNull
                        ?: block["content"]?.let { inner ->
                            if (inner is JsonObject) inner["text"]?.jsonPrimitive?.contentOrNull else null
                        }
                    text?.let { sb.append(it) }
                }
            }
            return sb.toString().ifEmpty { null }
        }

        if (contentElement is JsonPrimitive) {
            return contentElement.contentOrNull
        }

        return null
    }
}
