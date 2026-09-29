package banghak.stock.engine.application.market

import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.CredentialStatus
import banghak.stock.core.domain.error.DomainException
import banghak.stock.core.domain.market.ListingBoard
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.StockProfile
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.port.CredentialMetaPort
import banghak.stock.core.port.InstallationPort
import banghak.stock.core.port.StockCatalogPort
import banghak.stock.core.port.StockMasterPort
import banghak.stock.core.usecase.SyncStockMasterUseCase
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.locks.ReentrantLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 토스의 시장별 상장 종목 전체로 종목 마스터를 갱신함.
 * 모든 시장이 성공해야 동기화 시각을 남기고, 실패한 시장은 다음 확인 때 다시 받음.
 */
@Service
@Profile(RuntimeProfiles.ENGINE)
class StockMasterSyncService(
    private val catalog: StockCatalogPort,
    private val master: StockMasterPort,
    private val installations: InstallationPort,
    private val credentials: CredentialMetaPort,
    private val clock: Clock,
) : SyncStockMasterUseCase {
    // 스케줄러와 다른 호출이 겹쳐도 한 번만 돌게 함(STOCK_ALL 한도 초당 1회를 나눠 쓰지 않도록)
    private val running = ReentrantLock()

    @Volatile private var retryNotBefore: Instant = Instant.MIN

    override fun syncIfStale() {
        if (!isReady() || !isStale() || clock.instant().isBefore(retryNotBefore)) return
        if (!running.tryLock()) return
        try {
            syncAllBoards()
        } finally {
            running.unlock()
        }
    }

    // admin 의 토스 키로 공용 정보를 받으므로 설치가 끝나고 그 키가 있어야 함
    private fun isReady(): Boolean {
        val installation = installations.load() ?: return false
        val admin = installation.adminUserId ?: return false
        return installation.setupState.isComplete &&
            credentials.findByUser(admin).any {
                it.kind == CredentialKind.TOSS && it.status in USABLE_KEY
            }
    }

    private fun isStale(): Boolean {
        val lastSynced = master.lastSyncedAt() ?: return true
        return lastSynced.isBefore(latestRefreshPoint())
    }

    // 오늘 갱신 기준 시각이 아직 안 왔으면 어제 기준 시각을 씀
    private fun latestRefreshPoint(): Instant {
        val now = clock.instant().atZone(Market.KR.zone)
        val today = now.with(DAILY_REFRESH_AT)
        return (if (now.isBefore(today)) today.minusDays(1) else today).toInstant()
    }

    private fun syncAllBoards() {
        val startedAt = clock.instant()
        val failed = ListingBoard.entries.filterNot { trySyncBoard(it, startedAt) }
        if (failed.isEmpty()) {
            master.recordSync(startedAt)
            log.info("종목 마스터 동기화 완료")
        } else {
            retryNotBefore = startedAt.plus(RETRY_AFTER_FAILURE)
            log.warn("종목 마스터 동기화 실패 시장 {}. {} 뒤 다시 시도함", failed, RETRY_AFTER_FAILURE)
        }
    }

    // 받은 종목 정보는 저장하되, 목록보다 적으면 그 시장을 실패로 봐 완료 시각을 남기지 않고 다시 받게 함
    private fun trySyncBoard(board: ListingBoard, at: Instant): Boolean =
        try {
            val symbols = catalog.listedSymbols(board)
            val profiles = symbols.chunked(StockCatalogPort.MAX_SYMBOLS).flatMap(catalog::profiles)
            master.saveAll(profiles, at)
            // KR_ETC 처럼 원래 빈 시장도 있음(실측 0건).
            // 빈 목록으로 상장폐지를 표시하면 일시 오류 한 번에 시장 전체가 폐지로 보이므로 건너뜀
            if (symbols.isNotEmpty()) master.markDelistedExcept(board, symbols.toSet(), at)
            isComplete(board, symbols, profiles)
        } catch (e: DomainException) {
            log.warn("종목 마스터 {} 실패({}): {}", board, e::class.simpleName, e.message)
            false
        }

    private fun isComplete(
        board: ListingBoard,
        symbols: List<Symbol>,
        profiles: List<StockProfile>,
    ): Boolean {
        val missing = symbols - profiles.map { it.symbol }.toSet()
        if (missing.isEmpty()) {
            log.info("종목 마스터 {}: {}종목", board, profiles.size)
            return true
        }
        log.warn(
            "종목 마스터 {}: 종목 정보 {}건이 빠짐(예: {})",
            board,
            missing.size,
            missing.take(MISSING_SAMPLE).joinToString { it.code },
        )
        return false
    }

    companion object {
        private val log = LoggerFactory.getLogger(StockMasterSyncService::class.java)

        // 토스는 종목 목록을 일 배치로 갱신함(시각 미공개).
        // 국내 프리마켓 08:00 전에 받도록 정함
        private val DAILY_REFRESH_AT: LocalTime = LocalTime.of(7, 0)
        private val RETRY_AFTER_FAILURE: Duration = Duration.ofMinutes(30)
        private const val MISSING_SAMPLE = 5
        private val USABLE_KEY = setOf(CredentialStatus.VERIFIED, CredentialStatus.UNREACHABLE)
    }
}
