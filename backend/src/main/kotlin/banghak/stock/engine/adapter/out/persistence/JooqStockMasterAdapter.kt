package banghak.stock.engine.adapter.out.persistence

import banghak.stock.core.domain.market.ListingBoard
import banghak.stock.core.domain.market.StockProfile
import banghak.stock.core.domain.market.Symbol
import banghak.stock.core.port.StockMasterPort
import banghak.stock.engine.adapter.out.persistence.converter.InstantTextConverter
import banghak.stock.engine.adapter.out.persistence.repository.InstallationRepository
import banghak.stock.shared.config.RuntimeProfiles
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 종목 마스터를 jOOQ 일괄 upsert 로 저장함.
 * 토스가 채우지 않는 열(약명·업종·시총·출처 키)은 KRX·DART 보강이 채우므로 덮어쓰지 않음.
 */
@Component
@Profile(RuntimeProfiles.ENGINE)
class JooqStockMasterAdapter(
    private val dsl: DSLContext,
    private val installations: InstallationRepository,
) : StockMasterPort {
    @Transactional
    override fun saveAll(profiles: List<StockProfile>, at: Instant) {
        profiles.chunked(ROWS_PER_STATEMENT).forEach { chunk -> upsert(chunk, at) }
    }

    @Transactional
    override fun markDelistedExcept(board: ListingBoard, listed: Set<Symbol>, at: Instant) {
        val codes = listed.filter { it.market == board.market }.map { it.code }
        dsl.update(STOCK_MASTER)
            .set(DELISTED, 1)
            .set(UPDATED_AT, text(at))
            .where(LISTING_BOARD.eq(board.name))
            .and(DELISTED.eq(0))
            .and(CODE.notIn(codes))
            .execute()
    }

    @Transactional(readOnly = true)
    override fun lastSyncedAt(): Instant? =
        installations.findAll().firstOrNull()?.stockMasterSyncedAt

    @Transactional
    override fun recordSync(at: Instant) {
        val installation = installations.findAll().firstOrNull() ?: return
        installation.stockMasterSyncedAt = at
        installations.save(installation)
    }

    private fun upsert(profiles: List<StockProfile>, at: Instant) {
        val insert =
            profiles.fold(dsl.insertInto(STOCK_MASTER).columns(COLUMNS)) { step, profile ->
                step.values(rowOf(profile, at))
            }
        insert
            .onConflict(MARKET, CODE)
            .doUpdate()
            .set(UPDATABLE_COLUMNS.associateWith { DSL.excluded(it) })
            .execute()
    }

    private fun rowOf(profile: StockProfile, at: Instant): List<Any?> =
        listOf(
            profile.symbol.market.name,
            profile.symbol.code,
            profile.name,
            profile.englishName,
            profile.chosung,
            profile.isin,
            profile.securityType.name,
            flag(profile.isPreferred),
            profile.listedOn?.toString(),
            flag(profile.isDelisted),
            profile.leverageFactor?.toPlainString(),
            flag(profile.krDetail?.isNxtSupported == true),
            profile.board.name,
            text(at),
        )

    private fun flag(value: Boolean): Int = if (value) 1 else 0

    private fun text(at: Instant): String = InstantTextConverter.FORMAT.format(at)

    companion object {
        // 행마다 열 14개라 SQLite 바인드 변수 한도(32766) 안에서 넉넉히 끊음
        private const val ROWS_PER_STATEMENT = 500

        private val STOCK_MASTER = DSL.table("stock_master")
        private val MARKET: Field<String> = DSL.field("market", String::class.java)
        private val CODE: Field<String> = DSL.field("code", String::class.java)
        private val DELISTED: Field<Int> = DSL.field("delisted", Int::class.java)
        private val LISTING_BOARD: Field<String> = DSL.field("listing_board", String::class.java)
        private val UPDATED_AT: Field<String> = DSL.field("updated_at", String::class.java)
        private val COLUMNS: List<Field<*>> =
            listOf(
                MARKET,
                CODE,
                DSL.field("name", String::class.java),
                DSL.field("name_en", String::class.java),
                DSL.field("chosung", String::class.java),
                DSL.field("isin", String::class.java),
                DSL.field("security_group", String::class.java),
                DSL.field("is_preferred", Int::class.java),
                DSL.field("listed_at", String::class.java),
                DELISTED,
                DSL.field("leverage_multiple", String::class.java),
                DSL.field("nxt_supported", Int::class.java),
                LISTING_BOARD,
                UPDATED_AT,
            )
        private val UPDATABLE_COLUMNS = COLUMNS - setOf(MARKET, CODE)
    }
}
