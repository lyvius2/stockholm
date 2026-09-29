package banghak.stock.core.domain.market

/**
 * 한글 초성 검색 키.
 * 한글 음절은 초성으로 바꾸고 나머지 글자는 그대로 둠.
 */
object Chosung {
    private const val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val FIRST_SYLLABLE = 0xAC00
    private const val LAST_SYLLABLE = 0xD7A3

    // 초성 하나에 중성 21개 × 종성 28개가 딸림(유니코드 한글 음절 배열)
    private const val SYLLABLES_PER_INITIAL = 21 * 28

    fun of(text: String): String =
        text
            .map { letter ->
                if (letter.code in FIRST_SYLLABLE..LAST_SYLLABLE)
                    INITIALS[(letter.code - FIRST_SYLLABLE) / SYLLABLES_PER_INITIAL]
                else letter
            }
            .joinToString("")
}
