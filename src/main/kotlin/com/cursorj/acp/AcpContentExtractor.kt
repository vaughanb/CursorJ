package com.cursorj.acp

import kotlinx.serialization.json.*

object AcpContentExtractor {

    fun extractTextFromContent(contentElement: JsonElement?): String? {
        if (contentElement == null || contentElement is JsonNull) return null

        if (contentElement is JsonObject) {
            textValue(contentElement["text"])?.let { return it }
            val inner = contentElement["content"]
            if (inner is JsonObject) {
                return textValue(inner["text"])
            }
        }

        if (contentElement is JsonArray) {
            val sb = StringBuilder()
            for (block in contentElement) {
                if (block is JsonObject) {
                    val text = textValue(block["text"])
                        ?: block["content"]?.let { inner ->
                            if (inner is JsonObject) textValue(inner["text"]) else null
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

    private fun textValue(element: JsonElement?): String? {
        return (element as? JsonPrimitive)?.contentOrNull
    }
}
