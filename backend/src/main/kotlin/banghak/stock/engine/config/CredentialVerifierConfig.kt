package banghak.stock.engine.config

import banghak.stock.core.domain.account.CredentialKind
import banghak.stock.core.port.CredentialVerifier
import banghak.stock.engine.adapter.out.credential.FormatCredentialVerifier
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile(RuntimeProfiles.ENGINE)
class CredentialVerifierConfig {
    @Bean
    fun formatCredentialVerifiers(): List<CredentialVerifier> =
        CredentialKind.entries.map(::FormatCredentialVerifier)
}
