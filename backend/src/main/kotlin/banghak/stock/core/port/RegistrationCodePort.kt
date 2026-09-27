package banghak.stock.core.port

import banghak.stock.core.domain.account.RegistrationCode

interface RegistrationCodePort {
    fun save(code: RegistrationCode)

    fun findUnused(): List<RegistrationCode>
}
