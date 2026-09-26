package banghak.stock.engine.adapter.out.keychain

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * macOS Keychain 저장 설정.
 *
 * @property account 항목의 계정 이름. 제품은 `stockholm`, 개발 키는 `stockholm-dev`, 테스트는 임시 이름
 */
@ConfigurationProperties("stockholm.keychain")
data class KeychainProperties(val account: String = "stockholm")
