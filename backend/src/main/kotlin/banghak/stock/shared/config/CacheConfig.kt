package banghak.stock.shared.config

import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Configuration

/**
 * 캐시 대상은 종목 검색·현재가 스냅샷·환율·지수·RAG 검색 결과·요약뿐임. 비밀·계좌·주문·lot은 캐시하지 않음. 기본은 Caffeine(application.yml).
 * 등록된 Valkey/Redis로의 런타임 전환은 2단계에서 붙임.
 */
@Configuration @EnableCaching class CacheConfig
