package banghak.stock.core.port

import banghak.stock.core.domain.account.Session
import banghak.stock.core.domain.identity.UserId

interface SessionPort {
    fun save(session: Session)

    fun findById(sessionId: String): Session?

    fun findActiveByUserId(userId: UserId): List<Session>
}
