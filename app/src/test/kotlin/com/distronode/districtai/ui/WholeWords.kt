package com.distronode.districtai.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.text.TextLayoutResult

/**
 * Every word on screen that a line break cuts in two, as `"Overvie|w"`.
 *
 * ⛔ A WORD BROKEN MID-WAY IS WHAT A CONTROL TOO NARROW FOR ITS LABEL LOOKS LIKE. Compose wraps at
 * spaces while it can and splits a word only when that one word is wider than the space it has, so
 * a split word means the layout squeezed a label below the width of its own longest word. That is
 * the failure French (about 30% longer than English) and the en-XA pseudo-locale find, and it
 * survives every check that only asks whether the text is displayed.
 *
 * [withinTags] limits the search to the text under those test tags, so a test about one control is
 * not failed by another control on the same narrow screen.
 *
 * ⚠️ READ OFF THE TEXT LAYOUT ITSELF, through the semantics action every `Text` publishes, so it
 * sees the line breaks the layout actually chose rather than a width estimate.
 */
internal fun SemanticsNodeInteractionsProvider.wordsBrokenAcrossLines(withinTags: List<String>): List<String> =
    onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
            withinTags.map { hasAnyAncestor(hasTestTag(it)) or hasTestTag(it) }.reduce { a, b -> a or b },
        useUnmergedTree = true,
    )
        .fetchSemanticsNodes()
        .flatMap { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            layouts.flatMap { it.brokenWords() }
        }

private fun TextLayoutResult.brokenWords(): List<String> {
    val text = layoutInput.text.text
    return (0 until lineCount - 1).mapNotNull { line ->
        val end = getLineEnd(line)
        val split = end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
        if (split) {
            val start = text.lastIndexOfAny(charArrayOf(' ', '\n'), end - 1) + 1
            val stop = text.indexOfAny(charArrayOf(' ', '\n'), end).let { if (it < 0) text.length else it }
            text.substring(start, end) + "|" + text.substring(end, stop)
        } else {
            null
        }
    }
}
