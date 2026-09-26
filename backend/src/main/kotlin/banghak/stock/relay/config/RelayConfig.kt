package banghak.stock.relay.config

import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/** 중계 서버 조립의 뿌리. `relay` 프로필에서만 로드됨. 7단계 전까지는 뼈대만 둠. */
@Configuration @Profile(RuntimeProfiles.RELAY) class RelayConfig
