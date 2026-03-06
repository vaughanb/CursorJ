package com.cursorj.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object AcpContentExtractor {
    fun extractTextFromContent(contentElement: JsonElement?): String? {
        if (contentElement == null || contentElement is JsonNull) return null

        if (contentElement is JsonObject) {
            return contentElement["text"]?.jsonPrimitive?.contentOrNull
        }

        if (contentElement is JsonArray) {
            val sb = StringBuilder()
            for (block in contentElement) {
                if (block is JsonObject) {
                    block["text"]?.jsonPrimitive?.contentOrNull?.let { sb.append(it) }
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
