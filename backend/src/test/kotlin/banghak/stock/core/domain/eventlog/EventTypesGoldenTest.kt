package banghak.stock.core.domain.eventlog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 이벤트 클래스 이름은 저장 형식(`event_log.type`)임. 이름을 바꾸면 이 테스트가 깨지며, 그때는 저장된 이벤트의 마이그레이션을 함께 준비해야 함. */
class EventTypesGoldenTest {
    @Test
    @DisplayName("이벤트 클래스 이름 목록이 고정 목록과 같음")
    fun eventTypeNamesMatchGoldenList() {
        val actual = DomainEvent::class.sealedSubclasses.mapNotNull { it.simpleName }.sorted()
        assertThat(actual).containsExactlyElementsOf(GOLDEN.sorted())
    }

    companion object {
        private val GOLDEN =
            listOf(
                "LotOpened",
                "LotReduced",
                "LotClosed",
                "LotAgedOutOfAutoBuy",
                "OrderIntended",
                "OrderSubmitted",
                "OrderAmendRequested",
                "OrderCancelRequested",
                "OrderStatusChanged",
                "OrderFilled",
                "OrderResultUnknown",
                "GuardrailEvaluated",
                "AutomationSettingChanged",
                "LimitsLowered",
                "LimitRaiseIgnored",
                "KillSwitchChanged",
                "AutoSellPermissionChanged",
                "SimulatedOrderRecorded",
                "AutoBuyExclusionChanged",
                "ApprovalRequested",
                "ApprovalDecided",
                "ApprovalExpired",
                "DebateStarted",
                "PersonaSpoke",
                "UserIntervened",
                "RoundEnded",
                "VerdictReached",
                "DebateResumed",
                "WatchlistChanged",
                "PersonaDefinitionChanged",
                "UserSettingChanged",
                "LayoutRatioChanged",
                "ChartModeChanged",
                "JournalMemoChanged",
                "LeaseAcquired",
                "LeaseRenewed",
                "LeaseLost",
                "LeaseForciblyTaken",
            )
    }
}
