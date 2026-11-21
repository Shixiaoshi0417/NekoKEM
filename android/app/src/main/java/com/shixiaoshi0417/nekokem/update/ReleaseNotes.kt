package com.shixiaoshi0417.nekokem.update

/** One line of release notes, shown as plain text. */
data class NoteLine(val kind: Kind, val text: String) {
    enum class Kind { HEADING, ITEM, TEXT }
}

/**
 * Turns the Markdown of a release into short plain-text lines. The release
 * pages carry a Chinese and an English part ("## 简体中文", "## English");
 * only the part for the App's language is kept. Links, emphasis and code
 * marks are dropped, so nothing in the notes is clickable. Code blocks,
 * which hold checksums for the release page, are left out.
 */
object ReleaseNotes {
    /** Notes beyond this many characters are not read. */
    const val MAX_INPUT_CHARS = 64 * 1024
    const val MAX_LINES = 400
    const val MAX_LINE_CHARS = 2000

    private val LINK = Regex("""!?\[([^\]]*)]\([^)]*\)""")
    private val TABLE_RULE = Regex("""^\|?[\s:|-]+\|?$""")
    private val ORDERED = Regex("""^\d{1,3}[.)]\s+""")

    fun lines(markdown: String, chinese: Boolean): List<NoteLine> {
        val source = markdown.take(MAX_INPUT_CHARS).replace("\r\n", "\n").replace('\r', '\n').lines()
        val part = section(source, chinese)
        val result = ArrayList<NoteLine>()
        var code = false
        for ((index, raw) in part.withIndex()) {
            if (result.size >= MAX_LINES) break
            val line = raw.trim()
            if (line.startsWith("```") || line.startsWith("~~~")) {
                code = !code
                continue
            }
            if (code) continue
            if (line.isEmpty() || line.startsWith("<!--") || line.all { it == '-' || it == '*' || it == '_' }) continue
            val next = part.getOrNull(index + 1)?.trim().orEmpty()
            val entry = when {
                line.startsWith("# ") -> null
                line.startsWith("#") -> NoteLine.Kind.HEADING to line.trimStart('#')
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> NoteLine.Kind.ITEM to line.substring(2)
                ORDERED.containsMatchIn(line) -> NoteLine.Kind.ITEM to line.replaceFirst(ORDERED, "")
                line.startsWith("|") && TABLE_RULE.matches(line) -> null
                // A table's header row is the one above its rule.
                line.startsWith("|") && next.startsWith("|") && TABLE_RULE.matches(next) -> null
                line.startsWith("|") -> NoteLine.Kind.TEXT to line.trim('|').split('|').joinToString("  ") { it.trim() }
                line.startsWith(">") -> NoteLine.Kind.TEXT to line.trimStart('>')
                else -> NoteLine.Kind.TEXT to line
            } ?: continue
            val text = inline(entry.second)
            if (text.isNotEmpty()) result += NoteLine(entry.first, text.take(MAX_LINE_CHARS))
        }
        return result
    }

    /** The lines of the part for the language, the English part otherwise, or all lines without language parts. */
    private fun section(lines: List<String>, chinese: Boolean): List<String> {
        val starts = lines.indices.filter { lines[it].startsWith("## ") }
        fun find(match: (String) -> Boolean) = starts.firstOrNull { match(lines[it].removePrefix("## ").trim()) }
        // Without a language part, "## " lines are ordinary headings.
        val start = (if (chinese) find { "中文" in it } else null)
            ?: find { it.equals("English", ignoreCase = true) }
            ?: return lines
        val end = starts.firstOrNull { it > start } ?: lines.size
        return lines.subList(start + 1, end)
    }

    private fun inline(text: String): String =
        LINK.replace(text) { if (it.value.startsWith("!")) "" else it.groupValues[1] }
            .replace("**", "")
            .replace("`", "")
            .replace("<br>", " ")
            .trim()
}
