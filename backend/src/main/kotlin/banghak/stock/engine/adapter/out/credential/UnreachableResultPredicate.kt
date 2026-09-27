package banghak.stock.engine.adapter.out.credential

import banghak.stock.core.domain.account.CredentialCheck
import java.util.function.Predicate

/**
 * 검증기는 5xx·한도 초과를 예외가 아니라 `Unreachable` 값으로 돌려줌.
 * 서킷 브레이커가 이 값도 실패로 세도록 설정(`record-result-predicate`)에서 참조함.
 * 키 거부(`Rejected`)는 상대 서버 탓이 아니므로 세지 않음.
 */
class UnreachableResultPredicate : Predicate<Any?> {
    override fun test(result: Any?): Boolean = result is CredentialCheck.Unreachable
}
