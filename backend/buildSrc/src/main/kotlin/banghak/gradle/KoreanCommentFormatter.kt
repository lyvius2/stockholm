package banghak.gradle

import com.diffplug.spotless.FormatterFunc
import java.io.Serializable

/**
 * 한국어 주석을 문장마다 줄 바꿈함. ktfmt 다음에 도는 Spotless 단계임.
 * ktfmt 는 KDoc 문단을 최대 폭에 맞춰 다시 채우므로, 그 결과를 문장 단위로 다시 나눠 규칙(한 줄에 한 문장)을 도구가 지키게 함.
 * 한글이 없는 문단, 코드 블록, 목록, 표는 손대지 않음.
 */
object KoreanCommentFormatter : FormatterFunc, Serializable {
    private const val MAX_WIDTH = 100
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+(?=\\S)")
    private val HANGUL = Regex("[가-힣]")
    private val KDOC = Regex("(?m)^([ \\t]*)/\\*\\*(.*?)\\*/", RegexOption.DOT_MATCHES_ALL)
    private val LINE_COMMENT = Regex("^([ \\t]*)// (.*)$")
    private val VERBATIM_START = Regex("^(@|[-*] |\\d+\\. |\\| |```|#)")

    override fun apply(input: String): String = formatLineComments(formatKdocs(input))

    private fun formatKdocs(text: String): String =
        KDOC.replace(text) { match ->
            val indent = match.groupValues[1]
            val body = match.groupValues[2]
            if (!HANGUL.containsMatchIn(body)) match.value else renderKdoc(indent, paragraphsOf(body))
        }

    private fun paragraphsOf(body: String): List<Paragraph> {
        val lines = body.lines().map { it.trim().removePrefix("*").trimStart() }.map { it.trimEnd() }
        val paragraphs = mutableListOf<Paragraph>()
        var current = mutableListOf<String>()
        var inCode = false
        fun flush() {
            if (current.isNotEmpty()) paragraphs += Paragraph(current.toList(), verbatim = false)
            current = mutableListOf()
        }
        for (line in lines) {
            when {
                line.startsWith("```") -> {
                    flush()
                    paragraphs += Paragraph(listOf(line), verbatim = true)
                    inCode = !inCode
                }
                inCode || VERBATIM_START.containsMatchIn(line) -> {
                    flush()
                    paragraphs += Paragraph(listOf(line), verbatim = true)
                }
                line.isEmpty() -> {
                    flush()
                    paragraphs += Paragraph(emptyList(), verbatim = true)
                }
                else -> current += line
            }
        }
        flush()
        return paragraphs.dropWhile { it.isBlank }.dropLastWhile { it.isBlank }
    }

    private fun renderKdoc(indent: String, paragraphs: List<Paragraph>): String {
        val prefix = "$indent * "
        val lines =
            paragraphs.flatMap { paragraph ->
                when {
                    paragraph.isBlank -> listOf("")
                    paragraph.verbatim -> paragraph.lines
                    else -> sentencesOf(paragraph.lines).flatMap { wrap(it, prefix.length) }
                }
            }
        if (lines.size == 1 && indent.length + "/** ".length + lines[0].length + " */".length <= MAX_WIDTH) {
            return "$indent/** ${lines[0]} */"
        }
        return buildString {
            append(indent).append("/**\n")
            for (line in lines) append(if (line.isEmpty()) "$indent *" else prefix + line).append('\n')
            append(indent).append(" */")
        }
    }

    private fun sentencesOf(lines: List<String>): List<String> =
        lines.joinToString(" ").split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }

    // 문장 하나가 최대 폭을 넘으면 그 문장 안에서만 낱말 단위로 접음
    private fun wrap(sentence: String, prefixLength: Int): List<String> {
        val width = MAX_WIDTH - prefixLength
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (word in sentence.split(' ')) {
            if (line.isNotEmpty() && line.length + 1 + word.length > width) {
                out += line.toString()
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }

    private fun formatLineComments(text: String): String =
        text.lines().joinToString("\n") { line ->
            val match = LINE_COMMENT.matchEntire(line) ?: return@joinToString line
            val comment = match.groupValues[2]
            if (!HANGUL.containsMatchIn(comment)) return@joinToString line
            sentencesOf(listOf(comment)).joinToString("\n") { "${match.groupValues[1]}// $it" }
        }

    private data class Paragraph(val lines: List<String>, val verbatim: Boolean) {
        val isBlank: Boolean
            get() = lines.isEmpty()
    }
}
