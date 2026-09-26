package banghak.stock.core.domain.account

/**
 * TOTP 등록에 화면이 필요한 것. 시드 문자열은 수동 키로 한 번만 보이고 QR 은 데몬이 이미지로 만듦.
 *
 * @property qrPng otpauth URI 를 담은 PNG
 * @property manualKey base32 시드. "수동 키 보기" 에서 한 번만 보임
 */
class TotpEnrollment(val qrPng: ByteArray, val manualKey: String) {
    override fun toString(): String = "TotpEnrollment(****)"
}
