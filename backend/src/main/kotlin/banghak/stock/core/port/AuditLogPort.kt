package banghak.stock.core.port

import banghak.stock.core.domain.account.AuditEntry

interface AuditLogPort {
    fun record(entry: AuditEntry)
}
