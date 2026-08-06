package com.cursorj.ui.chat

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Insets

/**
 * [FlowLayout] variant that reports a wrapped preferred height based on the target width.
 * Standard [FlowLayout] uses unbounded width for preferred size, so wrapped chip rows get clipped.
 */
internal class WrapFlowLayout(
    align: Int = LEFT,
    hgap: Int = 0,
    vgap: Int = 0,
) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension =
        layoutSize(target, usePreferredSize = true)

    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, usePreferredSize = false)

    private fun layoutSize(target: Container, usePreferredSize: Boolean): Dimension {
        synchronized(target.treeLock) {
            val maxWidth = layoutWidth(target)
            if (maxWidth <= 0 || maxWidth == Int.MAX_VALUE) {
                return super.preferredLayoutSize(target)
            }

            val insets = target.insets
            var rowWidth = 0
            var rowHeight = 0
            var totalHeight = 0
            var widestRow = 0

            for (component in target.components) {
                if (!component.isVisible) continue
                val size = componentSize(component, usePreferredSize)
                val leadingGap = if (rowWidth == 0) 0 else hgap
                if (rowWidth > 0 && rowWidth + leadingGap + size.width > maxWidth) {
                    totalHeight += rowHeight + vgap
                    widestRow = widestRow.coerceAtLeast(rowWidth)
                    rowWidth = 0
                    rowHeight = 0
                }

                val gap = if (rowWidth == 0) 0 else hgap
                rowWidth += gap + size.width
                rowHeight = rowHeight.coerceAtLeast(size.height)
            }

            totalHeight += rowHeight
            widestRow = widestRow.coerceAtLeast(rowWidth)

            return Dimension(
                insets.left + insets.right + widestRow.coerceAtMost(maxWidth),
                insets.top + insets.bottom + totalHeight.coerceAtLeast(0),
            )
        }
    }

    private fun componentSize(component: Component, usePreferredSize: Boolean): Dimension {
        return if (usePreferredSize) component.preferredSize else component.minimumSize
    }

    private fun layoutWidth(target: Container): Int {
        if (target.width > 0) {
            return target.width - horizontalInsets(target.insets)
        }

        var parent: Container? = target.parent
        while (parent != null) {
            if (parent.width > 0) {
                val width = parent.width - horizontalInsets(target.insets) - horizontalInsets(parent.insets)
                if (width > 0) return width
            }
            parent = parent.parent
        }
        return Int.MAX_VALUE
    }

    private fun horizontalInsets(insets: Insets): Int = insets.left + insets.right
}
