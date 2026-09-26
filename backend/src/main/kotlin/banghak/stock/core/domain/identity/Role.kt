package banghak.stock.core.domain.identity

/** admin은 회원 관리와 공유 키 관리까지만. 타인의 매매 데이터에는 닿지 못함. */
enum class Role {
    ADMIN,
    MEMBER,
}
