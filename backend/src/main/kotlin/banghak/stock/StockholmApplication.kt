package banghak.stock

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * Stockholm 백엔드 진입점. 같은 산출물을 프로필로 나눠 씀: `engine`(클라이언트 데몬) · `relay`(중계 서버) · 둘 다(Mac mini 겸용).
 * 프로필이 하나도 없으면 [banghak.stock.shared.config.ProfileGuard]가 기동을 막음.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("banghak.stock.shared")
class StockholmApplication

fun main(args: Array<String>) {
    runApplication<StockholmApplication>(*args)
}
