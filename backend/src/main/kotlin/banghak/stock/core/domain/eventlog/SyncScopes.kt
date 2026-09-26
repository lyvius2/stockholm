package banghak.stock.core.domain.eventlog

/** 이벤트 종류 → 동기화 범위. 표는 이곳 하나뿐이며 `when`이 모든 이벤트를 강제로 덮음. */
object SyncScopes {
    fun of(event: DomainEvent): SyncScope =
        when (event) {
            is LotOpened,
            is LotReduced,
            is LotClosed,
            is LotAgedOutOfAutoBuy,
            is OrderIntended,
            is OrderSubmitted,
            is OrderAmendRequested,
            is OrderCancelRequested,
            is OrderStatusChanged,
            is OrderFilled,
            is OrderResultUnknown,
            is GuardrailEvaluated,
            is SimulatedOrderRecorded -> SyncScope.LOCAL
            is AutomationSettingChanged,
            is LimitsLowered,
            is LimitRaiseIgnored,
            is KillSwitchChanged,
            is AutoSellPermissionChanged,
            is AutoBuyExclusionChanged,
            is ApprovalRequested,
            is ApprovalDecided,
            is ApprovalExpired,
            is DebateStarted,
            is PersonaSpoke,
            is UserIntervened,
            is RoundEnded,
            is VerdictReached,
            is DebateResumed,
            is WatchlistChanged,
            is PersonaDefinitionChanged,
            is UserSettingChanged,
            is LayoutRatioChanged,
            is ChartModeChanged,
            is LeaseAcquired,
            is LeaseRenewed,
            is LeaseLost,
            is LeaseForciblyTaken -> SyncScope.USER
            is JournalMemoChanged -> SyncScope.FAMILY
        }
}
