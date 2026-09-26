package banghak.stock.shared.config

/** 실행 프로필 이름. `@Profile` 값은 컴파일 상수여야 하므로 enum 대신 상수로 둠. */
object RuntimeProfiles {
    const val ENGINE = "engine"
    const val RELAY = "relay"

    val ALL: Set<String> = setOf(ENGINE, RELAY)
}
