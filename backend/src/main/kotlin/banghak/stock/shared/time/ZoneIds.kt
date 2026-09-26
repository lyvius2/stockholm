package banghak.stock.shared.time

import java.time.ZoneId
import java.time.ZoneOffset

/** 저장은 UTC, 장 시간 계산은 서울·뉴욕을 명시함. 고정 KST 오프셋을 쓰지 않음(서머타임). */
object ZoneIds {
    val UTC: ZoneId = ZoneOffset.UTC
    val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
}
