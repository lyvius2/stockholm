package banghak.stock.engine.config

import banghak.stock.engine.adapter.out.keychain.KeychainProperties
import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile(RuntimeProfiles.ENGINE)
@EnableConfigurationProperties(KeychainProperties::class)
class KeychainConfig
