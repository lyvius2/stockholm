package banghak.stock.shared.crypto

/** RFC 4648 base32(패딩 없음). TOTP 시드 표기용. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val DECODE = ALPHABET.withIndex().associate { (index, char) -> char to index }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(buffer ushr bits) and 0x1F])
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.uppercase().filter { it != '=' && !it.isWhitespace() }
        val out = ByteArray(clean.length * 5 / 8)
        var buffer = 0
        var bits = 0
        var index = 0
        for (char in clean) {
            val value = DECODE[char] ?: throw IllegalArgumentException("base32 가 아닌 문자: '$char'")
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[index++] = ((buffer ushr bits) and 0xFF).toByte()
            }
        }
        return out
    }
}
