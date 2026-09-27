package banghak.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class KoreanCommentFormatterTest {
    private fun format(text: String) = KoreanCommentFormatter.apply(text)

    @Test
    @DisplayName("ktfmt 가 채운 한국어 KDoc 문단을 문장마다 줄 바꿈함")
    fun splitsKdocParagraphIntoSentences() {
        val input =
            """
            /**
             * 첫 문장임. 둘째 문장은 `code.value` 를 가짐. 셋째
             * 문장임.
             */
            class A
            """
                .trimIndent()
        val expected =
            """
            /**
             * 첫 문장임.
             * 둘째 문장은 `code.value` 를 가짐.
             * 셋째 문장임.
             */
            class A
            """
                .trimIndent()
        assertEquals(expected, format(input))
        assertEquals(expected, format(expected))
    }

    @Test
    @DisplayName("문장 하나짜리 KDoc 은 한 줄로 두고, 긴 문장은 문장 안에서만 접음")
    fun keepsSingleSentenceOnOneLineAndWrapsLongSentence() {
        assertEquals("    /** 한 문장임. */", format("    /**\n     * 한 문장임.\n     */"))
        val long = "가나다 ".repeat(40).trim() + "임."
        val result = format("/** $long 짧음. */")
        val lines = result.lines()
        assertEquals("/**", lines.first())
        assertEquals(" */", lines.last())
        assert(lines.drop(1).dropLast(1).all { it.length <= 100 })
        assertEquals(" * 짧음.", lines[lines.size - 2])
    }

    @Test
    @DisplayName("태그·목록·코드 블록·영문 문단은 그대로 두고 빈 줄은 유지함")
    fun leavesTagsListsCodeAndEnglishAlone() {
        val input =
            """
            /**
             * 요약임. 설명임.
             *
             * - 항목 하나. 항목 둘.
             * ```
             * val x = 1. val y = 2.
             * ```
             * Plain English. Two sentences.
             *
             * @param a 첫째임. 둘째임.
             */
            """
                .trimIndent()
        val expected =
            """
            /**
             * 요약임.
             * 설명임.
             *
             * - 항목 하나. 항목 둘.
             * ```
             * val x = 1. val y = 2.
             * ```
             * Plain English.
             * Two sentences.
             *
             * @param a 첫째임. 둘째임.
             */
            """
                .trimIndent()
        assertEquals(expected, format(input))
    }

    @Test
    @DisplayName("한 줄 주석에 문장이 둘이면 같은 들여쓰기의 두 줄로 나눔")
    fun splitsLineComments() {
        val input = "    // 먼저 확인함. 그다음 저장함.\n    val x = 1 // 영문 only. keep.\n"
        val expected = "    // 먼저 확인함.\n    // 그다음 저장함.\n    val x = 1 // 영문 only. keep.\n"
        assertEquals(expected, format(input))
    }
}
