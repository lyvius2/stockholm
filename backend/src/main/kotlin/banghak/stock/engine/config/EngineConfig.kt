package banghak.stock.engine.config

import banghak.stock.shared.config.RuntimeProfiles
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/** 클라이언트 데몬 조립의 뿌리. `engine` 프로필에서만 로드됨. 어댑터·usecase 구현이 생기면 여기서 조립함. */
@Configuration @Profile(RuntimeProfiles.ENGINE) class EngineConfig
