package banghak.stock.engine.adapter.out.persistence

import javax.sql.DataSource
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 읽기 전용 트랜잭션은 읽기 풀, 그 밖은 쓰기 풀로 보냄. `LazyConnectionDataSourceProxy` 로 감싸야 트랜잭션의 readOnly 가 정해진 뒤에
 * 연결을 고름.
 */
class ReadWriteRoutingDataSource(write: DataSource, read: DataSource) :
    AbstractRoutingDataSource() {
    init {
        setTargetDataSources(mapOf<Any, Any>(Pool.WRITE to write, Pool.READ to read))
        setDefaultTargetDataSource(write)
        afterPropertiesSet()
    }

    override fun determineCurrentLookupKey(): Pool =
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) Pool.READ
        else Pool.WRITE

    enum class Pool {
        WRITE,
        READ,
    }
}
