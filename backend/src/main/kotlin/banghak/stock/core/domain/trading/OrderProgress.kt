package banghak.stock.core.domain.trading

/**
 * 로컬에 기록된 주문의 진행 정도.
 * 재동기로 받은 스냅샷이 그 사이 들어온 실시간 이벤트보다 오래됐을 수 있어, 뒤처진 기록으로 되돌리지 않는 데 씀.
 */
data class OrderProgress(val status: OrderStatus, val filledQuantity: Quantity) {
    /** [record] 가 이 진행보다 뒤처졌음(체결 수량이 줄거나 닫힌 주문을 다시 엶). */
    fun isAheadOf(record: BrokerOrderRecord): Boolean =
        record.filledQuantity.value < filledQuantity.value || (isClosed && record.isOpen)

    // 모름은 닫힌 것이 아님(무엇으로든 확정될 수 있음)
    private val isClosed: Boolean
        get() = !status.isOpen && status != OrderStatus.UNKNOWN
}

/**
 * 로컬에 기록된 주문 한 건의 진행과 출처 구분.
 * 상태 변경 이벤트는 Stockholm 이 낸 주문에만 남김(밖에서 낸 주문은 기록만 함).
 */
data class RecordedOrder(val progress: OrderProgress, val isPlacedByStockholm: Boolean)
