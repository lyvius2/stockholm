package banghak.stock.core.port

import banghak.stock.core.domain.identity.UserId
import banghak.stock.core.domain.trading.ConditionalOrderRecord
import banghak.stock.core.domain.trading.ConditionalOrderSubmission
import banghak.stock.core.domain.trading.ConditionalOrdersPage
import banghak.stock.core.domain.trading.ConditionalOrdersQuery

/**
 * 증권사 조건주문 포트.
 * 구현은 토스증권 어댑터 하나뿐이며 항상 해당 사용자 본인의 키로 부름.
 * 감시와 발동은 토스 서버가 하고, 발동으로 생긴 주문은 일반 주문 채널로 들어옴.
 * 결과를 모르는 요청은 [banghak.stock.core.domain.error.OrderResultUnknownException] 으로 올리고, 호출자는 목록 조회로
 * 확정하기 전에 재시도하지 않음.
 * 등록·수정은 이름이 `place` 로 시작함.
 * 경계 테스트가 이 접두어로 가드레일을 거친 곳에서만 부르게 잠금.
 */
interface ConditionalOrderPort {
    /** @return 토스가 발급한 조건주문 번호 */
    fun placeConditionalOrder(submission: ConditionalOrderSubmission): String

    /**
     * 조건주문 전체를 다시 설정함.
     * 토스는 기존 것을 취소하고 새로 만들어 새 번호를 발급하며 옛 번호는 무효가 됨.
     * 토스 수정 요청에는 멱등 키가 없음.
     *
     * @return 새 조건주문 번호
     */
    fun placeConditionalAmendment(
        conditionalOrderId: String,
        submission: ConditionalOrderSubmission,
    ): String

    fun cancelConditionalOrder(userId: UserId, conditionalOrderId: String)

    fun lookupConditionalOrder(userId: UserId, conditionalOrderId: String): ConditionalOrderRecord

    fun conditionalOrders(userId: UserId, query: ConditionalOrdersQuery): ConditionalOrdersPage
}
