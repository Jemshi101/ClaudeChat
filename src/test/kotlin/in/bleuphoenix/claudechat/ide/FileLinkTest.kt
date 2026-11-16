package `in`.bleuphoenix.claudechat.ide

import org.junit.Assert.assertEquals
import org.junit.Test

class FileLinkTest {

    @Test
    fun `parses line suffixes`() {
        assertEquals(FileLink("src/Foo.kt", 42), FileLink.parse("src/Foo.kt:42"))
        assertEquals(FileLink("src/Foo.kt", 10), FileLink.parse("claude-file:src/Foo.kt:10-20"))
        assertEquals(FileLink("src/Foo.kt", 7), FileLink.parse("src/Foo.kt#L7"))
        assertEquals(FileLink("src/Foo.kt", null), FileLink.parse("src/Foo.kt"))
        assertEquals(FileLink("/abs/Foo.java", 3), FileLink.parse("file:///abs/Foo.java:3"))
    }

    @Test
    fun `links paths in plain text`() {
        assertEquals(
            "See [feature/Activity.kt:354](claude-file:feature/Activity.kt:354) and [Foo.java](claude-file:Foo.java).",
            FileLink.linkifyMarkdown("See feature/Activity.kt:354 and Foo.java."),
        )
    }

    @Test
    fun `inline code that is a path becomes a plain link, in the link color`() {
        assertEquals(
            "In [app/Foo.kt:12](claude-file:app/Foo.kt:12), call `foo()`.",
            FileLink.linkifyMarkdown("In `app/Foo.kt:12`, call `foo()`."),
        )
        assertEquals(
            "See [lib/add\\_user\\_viewmodel.dart:57](claude-file:lib/add_user_viewmodel.dart:57).",
            FileLink.linkifyMarkdown("See `lib/add_user_viewmodel.dart:57`."),
        )
    }

    @Test
    fun `links absolute paths`() {
        assertEquals(
            "[/Users/me/app/build.gradle.kts:12](claude-file:/Users/me/app/build.gradle.kts:12)",
            FileLink.linkifyMarkdown("/Users/me/app/build.gradle.kts:12"),
        )
    }

    @Test
    fun `leaves links, URLs, code blocks and non-files alone`() {
        val markdown = """
            [b.json](https://x.dev/a/b.json) and https://example.com/path/file.json, version 1.2.3, e.g. this
            `val x = Foo.kt`
            ```kotlin
            // see Foo.kt:1
            ```
        """.trimIndent()
        assertEquals(markdown, FileLink.linkifyMarkdown(markdown))
    }

    @Test
    fun `links resume after a fenced block closes`() {
        val markdown = "```\nA.kt\n```\nB.kt"
        assertEquals("```\nA.kt\n```\n[B.kt](claude-file:B.kt)", FileLink.linkifyMarkdown(markdown))
    }
}
