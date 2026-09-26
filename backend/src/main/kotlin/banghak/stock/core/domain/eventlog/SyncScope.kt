package banghak.stock.core.domain.eventlog

/**
 * 이벤트의 동기화 범위. LOCAL은 이 디바이스 밖으로 나가지 않음(lot·주문·체결·가드레일 판정·모의 실행). USER는 같은 사용자의 디바이스 사이, FAMILY는 가족
 * 전체(금액 없는 메모·회고).
 */
enum class SyncScope {
    LOCAL,
    USER,
    FAMILY,
}
