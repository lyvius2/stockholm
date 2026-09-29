package banghak.stock.engine.application.market

import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.domain.account.CredentialMeta
import banghak.stock.core.domain.account.CredentialStatus
import banghak.stock.core.domain.account.Installation
import banghak.stock.core.domain.account.SetupState
import banghak.stock.core.domain.market.ListingBoard
import banghak.stock.core.domain.market.Market
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.domain.trading.TradingFixtures
import banghak.stock.support.MutableClock
import banghak.stock.support.fakes.FakeStockCatalog
import banghak.stock.support.fakes.MemoryCredentialMetaPort
import banghak.stock.support.fakes.MemoryInstallationPort
import banghak.stock.support.fakes.MemoryStockMaster
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StockMasterSyncServiceTest {
    private val admin = TradingFixtures.user
    private val clock = MutableClock(kst("2026-09-29T07:30:00"))
    private val catalog = FakeStockCatalog()
    private val master = MemoryStockMaster()
    private val installations = MemoryInstallationPort()
    private val credentials = MemoryCredentialMetaPort()
    private val service = StockMasterSyncService(catalog, master, installations, credentials, clock)
    private val samsung = Symbol(Market.KR, "005930")
    private val nvidia = Symbol(Market.US, "NVDA")

    @BeforeEach
    fun completeSetup() {
        installations.installation = installation(SetupState.COMPLETE)
        credentials.upsert(tossKey(CredentialStatus.VERIFIED))
        catalog.listed[ListingBoard.KOSPI] = listOf(samsung)
        catalog.listed[ListingBoard.NASDAQ] = listOf(nvidia)
    }

    @Test
    @DisplayName("모든 시장을 받아 저장하고 동기화 시각을 남기며, 빈 시장은 상장폐지 표시를 하지 않음")
    fun syncsEveryBoard() {
        service.syncIfStale()

        assertThat(catalog.listedCalls).containsExactlyElementsOf(ListingBoard.entries)
        assertThat(master.saved.keys).containsExactlyInAnyOrder(samsung, nvidia)
        assertThat(master.delistCalls)
            .containsExactly(
                ListingBoard.KOSPI to setOf(samsung),
                ListingBoard.NASDAQ to setOf(nvidia),
            )
        assertThat(master.syncedAt).isEqualTo(clock.instant())
    }

    @Test
    @DisplayName("종목 정보는 200개씩 나눠 받음")
    fun fetchesProfilesInChunksOfTwoHundred() {
        catalog.listed[ListingBoard.KOSDAQ] =
            (0 until 450).map { Symbol(Market.KR, "%06d".format(it)) }

        service.syncIfStale()

        assertThat(catalog.profileCalls.map { it.size }).contains(200, 200, 50)
        assertThat(master.saved).hasSize(452)
    }

    @Test
    @DisplayName("한 시장이 실패하면 동기화 시각을 남기지 않고 30분 뒤에야 다시 시도함")
    fun failedBoardBacksOff() {
        catalog.unavailableBoards += ListingBoard.NYSE

        service.syncIfStale()

        assertThat(master.syncedAt).isNull()
        assertThat(master.saved.keys).describedAs("성공한 시장은 저장됨").contains(samsung, nvidia)
        catalog.listedCalls.clear()
        clock.advance(Duration.ofMinutes(29))
        service.syncIfStale()
        assertThat(catalog.listedCalls).isEmpty()
        clock.advance(Duration.ofMinutes(1))
        service.syncIfStale()
        assertThat(catalog.listedCalls).isNotEmpty()
    }

    @Test
    @DisplayName("종목 정보가 목록보다 적게 오면 받은 것은 저장하되 그 시장을 실패로 봐 완료 시각을 남기지 않음")
    fun incompleteBoardIsFailure() {
        val hynix = Symbol(Market.KR, "000660")
        catalog.listed[ListingBoard.KOSPI] = listOf(samsung, hynix)
        catalog.droppedProfiles += hynix

        service.syncIfStale()

        assertThat(master.saved.keys).contains(samsung).doesNotContain(hynix)
        assertThat(master.syncedAt).isNull()
    }

    @Nested
    @DisplayName("동기화 조건")
    inner class Readiness {
        @Test
        @DisplayName("설치가 끝나지 않았으면 부르지 않음")
        fun skipsBeforeSetupComplete() {
            installations.installation = installation(SetupState.TOSS_DECIDED)

            service.syncIfStale()

            assertThat(catalog.listedCalls).isEmpty()
        }

        @Test
        @DisplayName("admin 의 토스 키가 없거나 거부된 상태면 부르지 않음")
        fun skipsWithoutUsableAdminKey() {
            credentials.delete(CredentialKind.TOSS, admin)
            service.syncIfStale()
            credentials.upsert(tossKey(CredentialStatus.REJECTED))
            service.syncIfStale()

            assertThat(catalog.listedCalls).isEmpty()
        }
    }

    @Nested
    @DisplayName("갱신 기준 시각(매일 07:00 KST)")
    inner class Staleness {
        @Test
        @DisplayName("오늘 07:00 직전에 받았으면 07:00 부터 다시 받음")
        fun staleJustBeforeRefreshPoint() {
            master.syncedAt = kst("2026-09-29T06:59:59")
            clock.moveTo(kst("2026-09-29T07:00:00"))

            service.syncIfStale()

            assertThat(catalog.listedCalls).isNotEmpty()
        }

        @Test
        @DisplayName("정확히 07:00 에 받았으면 그날은 다시 받지 않음")
        fun freshWhenSyncedExactlyAtRefreshPoint() {
            master.syncedAt = kst("2026-09-29T07:00:00")
            clock.moveTo(kst("2026-09-29T23:00:00"))

            service.syncIfStale()

            assertThat(catalog.listedCalls).isEmpty()
        }

        @Test
        @DisplayName("07:00 전이면 어제 07:00 이후에 받은 것을 그대로 씀")
        fun beforeTodaysRefreshPointUsesYesterdays() {
            master.syncedAt = kst("2026-09-28T07:00:00")
            clock.moveTo(kst("2026-09-29T06:59:59"))

            service.syncIfStale()

            assertThat(catalog.listedCalls).isEmpty()
        }
    }

    private fun installation(state: SetupState) =
        Installation(
            installationId = "i_1",
            setupState = state,
            adminUserId = admin,
            llmPreset = null,
            lastLoginUserId = null,
            createdAt = TradingFixtures.now,
            updatedAt = TradingFixtures.now,
        )

    private fun tossKey(status: CredentialStatus) =
        CredentialMeta(CredentialKind.TOSS, admin, status, "abcd", TradingFixtures.now, null)

    private fun kst(local: String): Instant = OffsetDateTime.parse("$local+09:00").toInstant()
}
