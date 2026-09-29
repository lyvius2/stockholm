package banghak.stock.core.usecase

/**
 * 체결 대기열을 lot 에 반영함.
 * 원장이 없는 사용자는 증권사 보유로 기초 lot 을 만들어 원장을 시작함.
 * 매수는 lot 을 만들고, 매도는 lot 을 선입선출로 소진해 실현손익을 남김.
 */
interface ProcessFillsUseCase {
    fun processFills()
}
