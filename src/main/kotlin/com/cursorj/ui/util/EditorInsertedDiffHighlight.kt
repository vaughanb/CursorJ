package com.cursorj.ui.util

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color

/**
 * "Added line" highlights when opening a file from chat must follow the **editor color scheme**
 * (paper + optional DIFF_* keys), not [com.intellij.ui.JBColor] / UI LaF — otherwise a dark theme with
 * a light editor yields unreadable contrast.
 */
object EditorInsertedDiffHighlight {

    private val diffInsertedKey = TextAttributesKey.createTextAttributesKey("DIFF_INSERTED")
    private val diffModifiedKey = TextAttributesKey.createTextAttributesKey("DIFF_MODIFIED")

    fun attributesForScheme(scheme: EditorColorsScheme): TextAttributes {
        val fromScheme = scheme.getAttributes(diffInsertedKey)
            ?: scheme.getAttributes(diffModifiedKey)
        val attrs = if (fromScheme != null) {
            fromScheme.clone()
        } else {
            fallbackTextAttributes(scheme.defaultBackground)
        }
        attrs.foregroundColor = null
        attrs.effectType = null
        attrs.effectColor = null
        val fb = fallbackBackgroundAndStripeForPaper(scheme.defaultBackground)
        if (attrs.backgroundColor == null) {
            attrs.backgroundColor = fb.first
        }
        if (attrs.errorStripeColor == null) {
            attrs.errorStripeColor = fb.second
        }
        return attrs
    }

    /** Background and error-stripe colors when the scheme has no DIFF_* attributes. */
    fun fallbackBackgroundAndStripeForPaper(editorPaper: Color): Pair<Color, Color> {
        val light = UiThemeBrightness.useLightHtmlPaletteForSurface(editorPaper)
        val bg = if (light) Color(0xEAF8EE) else Color(0x2D4A32)
        val stripe = if (light) Color(0x28A745) else Color(0x56D364)
        return bg to stripe
    }

    private fun fallbackTextAttributes(paper: Color): TextAttributes {
        val (bg, stripe) = fallbackBackgroundAndStripeForPaper(paper)
        return TextAttributes().apply {
            backgroundColor = bg
            errorStripeColor = stripe
        }
    }
}
