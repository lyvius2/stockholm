package banghak.stock.engine.adapter.out.keychain

import banghak.stock.core.domain.account.SecretKey
import banghak.stock.core.domain.account.SecretValue

/** 비밀값 읽기. 출력 어댑터(토스·LLM 등)만 쓰고 application·진입 어댑터는 참조하지 못함(ArchUnit). 값은 호출 직전에 꺼내 쓰고 `wipe` 함. */
interface SecretReader {
    /** 없으면 null. 값을 예외·로그에 싣지 않음. */
    fun read(key: SecretKey): SecretValue?
}
