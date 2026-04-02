package com.cursorj.ui.util

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Chooses light vs dark styling for embedded HTML (JEditorPane markdown, inline diff previews).
 *
 * [JBColor.isBright] can disagree with the visible editor (e.g. light editor scheme while the UI
 * reports a dark baseline). We prefer the **global editor color scheme** background so chat code
 * blocks match the editor surface the user is coding in.
 *
 * Chat **message bubbles** use their own [java.awt.Color] backgrounds (JBColor per LaF). HTML inside
 * those bubbles must follow **that** surface, not the editor scheme, or you get dark text on dark
 * bubbles when the editor is light and the IDE chat is dark.
 */
object UiThemeBrightness {

    /**
     * Use GitHub-style **light** HTML (dark text, pale code bg) when [surfaceBackground] is a light
     * surface; use **dark** HTML (light text) when the surface is dark.
     */
    fun useLightHtmlPaletteForSurface(surfaceBackground: Color): Boolean {
        return !isColorEffectivelyDark(surfaceBackground)
    }

    fun useLightPaletteForEmbeddedHtml(): Boolean {
        return try {
            val bg = EditorColorsManager.getInstance().globalScheme.defaultBackground
            !isColorEffectivelyDark(bg)
        } catch (_: Exception) {
            JBColor.isBright()
        }
    }

    /** True when sRGB luminance is on the dark side (similar to platform "dark editor" heuristics). */
    fun isColorEffectivelyDark(c: Color): Boolean {
        val r = c.red / 255.0
        val g = c.green / 255.0
        val b = c.blue / 255.0
        fun lin(v: Double): Double =
            if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        val l = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
        return l < 0.45
    }
}
