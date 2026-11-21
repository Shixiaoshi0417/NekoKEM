package com.shixiaoshi0417.nekokem.update

import com.shixiaoshi0417.nekokem.update.NoteLine.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    private val sample = """
        # NekoKEM v9.9.9

        ## 简体中文

        ### 本版更新

        本版加入 **新功能**。
        - **记录原文件名**：见 [说明](https://example.invalid/)。
        - 使用 `curl` 检查更新

        | 平台 | 正式附件 |
        |---|---|
        | Android ARM64 | `app-release.apk` |

        ```text
        0123abcd  app-release.apk
        ```

        ## English

        ### What's new

        1. First
        2) Second
        ---
        > Quoted ![image](https://example.invalid/a.png)
    """.trimIndent()

    @Test fun chineseAppsReadTheChinesePart() {
        assertEquals(
            listOf(
                NoteLine(Kind.HEADING, "本版更新"),
                NoteLine(Kind.TEXT, "本版加入 新功能。"),
                NoteLine(Kind.ITEM, "记录原文件名：见 说明。"),
                NoteLine(Kind.ITEM, "使用 curl 检查更新"),
                NoteLine(Kind.TEXT, "Android ARM64  app-release.apk"),
            ),
            ReleaseNotes.lines(sample, chinese = true),
        )
    }

    @Test fun otherAppsReadTheEnglishPart() {
        assertEquals(
            listOf(
                NoteLine(Kind.HEADING, "What's new"),
                NoteLine(Kind.ITEM, "First"),
                NoteLine(Kind.ITEM, "Second"),
                NoteLine(Kind.TEXT, "Quoted"),
            ),
            ReleaseNotes.lines(sample, chinese = false),
        )
    }

    @Test fun notesWithoutPartsAreReadWhole() {
        assertEquals(
            listOf(NoteLine(Kind.HEADING, "Changes"), NoteLine(Kind.ITEM, "One")),
            ReleaseNotes.lines("### Changes\r\n\r\n* One\r\n", chinese = true),
        )
        assertTrue(ReleaseNotes.lines("", chinese = false).isEmpty())
        assertEquals(
            listOf(
                NoteLine(Kind.TEXT, "Intro"),
                NoteLine(Kind.HEADING, "Changes"),
                NoteLine(Kind.ITEM, "Feature"),
                NoteLine(Kind.HEADING, "Compatibility"),
                NoteLine(Kind.ITEM, "Migrate"),
            ),
            ReleaseNotes.lines("Intro\n## Changes\n- Feature\n## Compatibility\n- Migrate", chinese = false),
        )
    }

    @Test fun aMissingLanguageFallsBackToEnglish() {
        val lines = ReleaseNotes.lines("## Deutsch\n- Eins\n## English\n- One", chinese = true)
        assertEquals(listOf(NoteLine(Kind.ITEM, "One")), lines)
    }

    @Test fun longNotesAreBounded() {
        assertEquals(ReleaseNotes.MAX_LINES, ReleaseNotes.lines("- x\n".repeat(1000), chinese = false).size)
        val long = ReleaseNotes.lines(("- " + "x".repeat(5000) + "\n").repeat(100), chinese = false)
        assertTrue(long.size < 100)
        assertEquals(ReleaseNotes.MAX_LINE_CHARS, long.first().text.length)
        assertTrue(long.all { it.text.length <= ReleaseNotes.MAX_LINE_CHARS })
    }

    @Test fun thePublishedNotesHaveBothParts() {
        val body = checkNotNull(javaClass.getResource("/release-notes-v5.2.0.md")).readText()
        val chinese = ReleaseNotes.lines(body, chinese = true)
        val english = ReleaseNotes.lines(body, chinese = false)
        assertEquals(NoteLine(Kind.HEADING, "本版更新"), chinese.first())
        assertEquals(NoteLine(Kind.HEADING, "What's new"), english.first())
        assertFalse(chinese.any { it.text.contains("What's new") || it.text.contains("](") || it.text.contains("**") })
    }
}
