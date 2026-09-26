package banghak.stock.core.port

import banghak.stock.core.domain.account.Installation

interface InstallationPort {
    fun load(): Installation?

    fun save(installation: Installation)
}
