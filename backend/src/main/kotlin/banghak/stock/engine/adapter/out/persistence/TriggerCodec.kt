package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.automation.StrategyId
import banghak.stock.core.domain.identity.DeviceId
import banghak.stock.core.domain.trading.AutoBuyTrigger
import banghak.stock.core.domain.trading.AutoSellTrigger
import banghak.stock.core.domain.trading.ManualAmendTrigger
import banghak.stock.core.domain.trading.ManualTrigger
import banghak.stock.core.domain.trading.OrderTrigger
import banghak.stock.core.domain.trading.RecommendationTrigger
import java.time.LocalDate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

/**
 * 주문 트리거 ↔ (`trigger_type`, `trigger_json`).
 * 종류 이름은 저장 형식이라 클래스 이름이 바뀌어도 그대로 둘 것.
 */
internal object TriggerCodec {
    /**
     * Stockholm 밖(토스 앱 등)에서 낸 주문.
     * 실시간 주문 채널로만 알게 됨.
     */
    const val EXTERNAL = "EXTERNAL"

    private val json = JsonMapper.builder().build()

    fun typeOf(trigger: OrderTrigger): String =
        when (trigger) {
            is ManualTrigger -> "MANUAL"
            is ManualAmendTrigger -> "MANUAL_AMEND"
            is RecommendationTrigger -> "RECOMMENDATION"
            is AutoBuyTrigger -> "AUTO_BUY"
            is AutoSellTrigger -> "AUTO_SELL"
        }

    fun jsonOf(trigger: OrderTrigger): String = json.writeValueAsString(fieldsOf(trigger))

    fun decode(type: String, text: String?): OrderTrigger {
        val fields: Map<String, String> = text?.let { json.readValue(it) } ?: emptyMap()
        val field = { name: String -> fields[name] ?: error("트리거 $type 에 $name 이 없음") }
        return when (type) {
            "MANUAL" -> ManualTrigger(DeviceId(field("device")))
            "MANUAL_AMEND" ->
                ManualAmendTrigger(DeviceId(field("device")), field("amendedBrokerOrderId"))
            "RECOMMENDATION" -> RecommendationTrigger(field("recommendationId"))
            "AUTO_BUY" ->
                AutoBuyTrigger(
                    StrategyId(field("strategy")),
                    LocalDate.parse(field("tradingDay")),
                    field("sequence").toInt(),
                )
            "AUTO_SELL" ->
                AutoSellTrigger(
                    field("debateSessionId"),
                    LocalDate.parse(field("tradingDay")),
                    field("sequence").toInt(),
                )
            else -> error("알 수 없는 트리거 종류: $type")
        }
    }

    private fun fieldsOf(trigger: OrderTrigger): Map<String, String> =
        when (trigger) {
            is ManualTrigger -> mapOf("device" to trigger.device.value)
            is ManualAmendTrigger ->
                mapOf(
                    "device" to trigger.device.value,
                    "amendedBrokerOrderId" to trigger.amendedBrokerOrderId,
                )
            is RecommendationTrigger -> mapOf("recommendationId" to trigger.recommendationId)
            is AutoBuyTrigger ->
                mapOf(
                    "strategy" to trigger.strategy.value,
                    "tradingDay" to trigger.tradingDay.toString(),
                    "sequence" to trigger.sequence.toString(),
                )
            is AutoSellTrigger ->
                mapOf(
                    "debateSessionId" to trigger.debateSessionId,
                    "tradingDay" to trigger.tradingDay.toString(),
                    "sequence" to trigger.sequence.toString(),
                )
        }
}
