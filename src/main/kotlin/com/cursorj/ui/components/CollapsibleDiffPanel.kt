package com.cursorj.ui.components

import com.cursorj.ui.util.UiThemeBrightness
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class CollapsibleDiffPanel(
    private val filePath: String,
    private val oldText: String,
    private val newText: String,
    private val onFileClick: ((path: String, line: Int, addedLines: List<Int>) -> Unit)? = null,
) {
    private val diffResult: DiffResult by lazy { LineDiff.compute(oldText, newText) }

    private val fileName: String = filePath
        .substringAfterLast('/')
        .substringAfterLast('\\')

    private var expanded = false

    private val borderColor = JBColor(Color(0xD4D4D4), Color(0x3B3B3B))
    private val headerBg = JBColor(Color(0xF5F5F5), Color(0x2D2D2D))
    private val headerHoverBg = JBColor(Color(0xEBEBEB), Color(0x353535))
    private val diffBodyBg = JBColor(Color(0xFAFAFA), Color(0x1E1E1E))
    private val expandAreaBg = JBColor(Color(0xF0F0F0), Color(0x252525))
    private val addedColor = JBColor(Color(0x28A745), Color(0x3FB950))
    private val removedColor = JBColor(Color(0xCB2431), Color(0xF85149))
    private val fileNameColor = JBColor(Color(0x333333), Color(0xCCCCCC))
    private val expandLinkColor = JBColor(Color(0x5890C8), Color(0x6B9BD2))

    private val diffBody = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.customLineTop(borderColor)
        isOpaque = true
        background = diffBodyBg
    }

    private val headerPanel = object : JPanel(BorderLayout()) {
        private var hovering = false

        init {
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hovering = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hovering = false; repaint() }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.color = if (hovering) headerHoverBg else headerBg
            g2.fillRect(0, 0, width, height)
        }
    }.apply {
        border = JBUI.Borders.empty(4, 8)
        isOpaque = false
    }

    private val panel = object : JPanel(BorderLayout()) {
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val insets = border?.getBorderInsets(this) ?: Insets(0, 0, 0, 0)
            val x = insets.left
            val y = insets.top
            val w = width - insets.left - insets.right
            val h = height - insets.top - insets.bottom
            g2.color = borderColor
            g2.drawRoundRect(x, y, w - 1, h - 1, 8, 8)
        }
    }.apply {
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(3, 10)
        isOpaque = false
    }

    val component: JComponent get() = panel

    private lateinit var allDiffLines: List<DiffLine>
    private lateinit var previewHtml: String
    private lateinit var fullHtml: String
    private lateinit var diffPane: JEditorPane

    private val contentWrapper = JPanel(BorderLayout()).apply {
        isOpaque = false
    }

    init {
        buildHeader()
        contentWrapper.add(headerPanel, BorderLayout.NORTH)
        contentWrapper.add(diffBody, BorderLayout.CENTER)
        panel.add(contentWrapper, BorderLayout.CENTER)
        buildDiffBody()
    }

    private fun buildHeader() {
        val info = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }

        val fileLabel = JLabel(fileName).apply {
            foreground = fileNameColor
            font = font.deriveFont(Font.BOLD, 12f)
            if (onFileClick != null) {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        onFileClick.invoke(filePath, diffResult.firstChangedLine, diffResult.addedLineNumbers)
                    }
                    override fun mouseEntered(e: MouseEvent) {
                        text = "<html><u>$fileName</u></html>"
                    }
                    override fun mouseExited(e: MouseEvent) {
                        text = fileName
                    }
                })
            }
        }
        info.add(fileLabel)

        val dr = diffResult
        if (dr.linesAdded > 0) {
            info.add(JLabel("  +${dr.linesAdded}").apply {
                foreground = addedColor
                font = font.deriveFont(11f)
            })
        }
        if (dr.linesRemoved > 0) {
            info.add(JLabel("  -${dr.linesRemoved}").apply {
                foreground = removedColor
                font = font.deriveFont(11f)
            })
        }

        headerPanel.add(info, BorderLayout.CENTER)
    }

    private fun buildDiffBody() {
        allDiffLines = diffResult.hunks.flatMapIndexed { i, hunk ->
            val sep = if (i > 0) listOf(DiffLine(DiffLineType.CONTEXT, "\u2026")) else emptyList()
            sep + hunk.lines
        }

        val needsExpand = allDiffLines.size > PREVIEW_LINE_COUNT

        previewHtml = buildDiffHtml(if (needsExpand) allDiffLines.take(PREVIEW_LINE_COUNT) else allDiffLines)
        fullHtml = if (needsExpand) buildDiffHtml(allDiffLines) else previewHtml

        diffPane = JEditorPane("text/html", previewHtml).apply {
            isEditable = false
            border = JBUI.Borders.empty(2, 0)
            isOpaque = true
            background = diffBodyBg
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        }
        diffBody.add(diffPane, BorderLayout.CENTER)

        if (needsExpand) {
            val remaining = allDiffLines.size - PREVIEW_LINE_COUNT
            val collapsedText = "Show $remaining more lines\u2026"
            val expandedText = "Show less"
            val expandLabel = JLabel(collapsedText).apply {
                foreground = expandLinkColor
                font = font.deriveFont(11f)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(2, 8, 4, 8)
                isOpaque = true
                background = expandAreaBg
            }
            expandLabel.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    expanded = !expanded
                    diffPane.text = if (expanded) fullHtml else previewHtml
                    expandLabel.text = if (expanded) expandedText else collapsedText
                    panel.revalidate()
                    panel.repaint()
                    SwingUtilities.invokeLater {
                        val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, panel)
                        (scrollPane as? JScrollPane)?.revalidate()
                    }
                }
                override fun mouseEntered(e: MouseEvent) {
                    val current = if (expanded) expandedText else collapsedText
                    expandLabel.text = "<html><u>$current</u></html>"
                }
                override fun mouseExited(e: MouseEvent) {
                    expandLabel.text = if (expanded) expandedText else collapsedText
                }
            })
            diffBody.add(expandLabel, BorderLayout.SOUTH)
        }
    }

    private fun buildDiffHtml(lines: List<DiffLine>): String {
        val light = UiThemeBrightness.useLightHtmlPaletteForSurface(diffBody.background)
        val bodyBg = if (light) "#fafafa" else "#1e1e1e"
        val addBg = if (light) "#dafbe1" else "#1a2e1a"
        val removeBg = if (light) "#ffebe9" else "#2e1a1a"
        val codeFg = if (light) "#24292f" else "#f0f6fc"
        val secondaryFg = if (light) "#57606a" else "#8b949e"
        val sepBg = if (light) "#f1f8ff" else "#161b22"
        val sepFg = if (light) "#0366d6" else "#58a6ff"

        val sb = StringBuilder()
        sb.append("<html><body style='font-family: Consolas, Menlo, monospace; font-size: 10pt; margin: 0; padding: 0; line-height: 1.35; background: $bodyBg;'>")

        if (lines.isEmpty()) {
            sb.append("<div style='color: $secondaryFg; padding: 4px;'>No visible changes</div>")
        }

        for (line in lines) {
            if (line.type == DiffLineType.CONTEXT && line.text == "\u2026") {
                sb.append("<div style='background: $sepBg; color: $sepFg; padding: 1px 6px; font-size: 9pt;'>\u2026</div>")
                continue
            }
            val bg = when (line.type) {
                DiffLineType.ADD -> addBg
                DiffLineType.REMOVE -> removeBg
                DiffLineType.CONTEXT -> bodyBg
            }
            val escaped = escapeHtml(line.text)
            sb.append("<pre style='background: $bg; color: $codeFg; padding: 0 6px; margin: 0;'>$escaped</pre>")
        }

        sb.append("</body></html>")
        return sb.toString()
    }

    /** Rebuild diff HTML so colors match the current editor scheme (e.g. after theme switch mid-session). */
    fun refreshTheme() {
        if (!::diffPane.isInitialized) return
        val needsExpand = allDiffLines.size > PREVIEW_LINE_COUNT
        previewHtml = buildDiffHtml(if (needsExpand) allDiffLines.take(PREVIEW_LINE_COUNT) else allDiffLines)
        fullHtml = if (needsExpand) buildDiffHtml(allDiffLines) else previewHtml
        diffPane.text = if (expanded && needsExpand) fullHtml else previewHtml
        diffBody.background = diffBodyBg
        diffPane.background = diffBodyBg
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("\t", "    ")
    }

    companion object {
        private const val PREVIEW_LINE_COUNT = 5
    }
}
