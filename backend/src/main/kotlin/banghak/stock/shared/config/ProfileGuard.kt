package banghak.stock.shared.config

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/** 실행 프로필 없이 뜬 컨텍스트는 아무 빈도 싣지 않은 빈 껍데기이므로 기동 자체를 막음. */
@Component
class ProfileGuard(environment: Environment) {
    init {
        val active = environment.activeProfiles.toSet()
        check(active.any { it in RuntimeProfiles.ALL }) {
            "실행 프로필이 없음. --spring.profiles.active=engine 또는 relay 로 기동할 것 (현재: $active)"
        }
    }
}
