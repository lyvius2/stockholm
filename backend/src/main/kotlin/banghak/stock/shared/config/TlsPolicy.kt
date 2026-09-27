package banghak.stock.shared.config

import okhttp3.ConnectionSpec

/**
 * 외부 API 접속에 쓰는 TLS 규칙. 버전은 TLS 1.2·1.3, 암호 묶음은 JDK 보안 정책(`jdk.tls.disabledAlgorithms`)이 켜 둔 것 전부임.
 */
object TlsPolicy {
    // OkHttp 기본(MODERN_TLS)은 ECDHE·TLS 1.3 묶음만 제시함.
    // DART(opendart.fss.or.kr)는 TLS 1.2에서 RSA·DHE 키 교환만 받고, RSA 묶음은 JDK 25가 기본 비활성이라 DHE가 유일한 접점임.
    // 국내 공공 API 서버에 같은 구성이 흔해 JDK가 허용한 묶음을 그대로 제시함(학습 테스트 DartTlsLearningTest).
    val connectionSpec: ConnectionSpec =
        ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).allEnabledCipherSuites().build()

    /** 위 TLS 규칙에 평문 접속을 더한 목록. 로컬 Ollama·캐시 서버·테스트 스텁은 `http://`로 붙음. */
    val connectionSpecs: List<ConnectionSpec> = listOf(connectionSpec, ConnectionSpec.CLEARTEXT)
}
