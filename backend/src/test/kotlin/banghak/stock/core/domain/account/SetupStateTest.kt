package banghak.stock.core.domain.account

import banghak.stock.core.domain.error.IllegalSetupTransitionException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SetupStateTest {
    @Test
    @DisplayName("전이는 한 칸씩 앞으로만 감")
    fun advancesOneStepOnly() {
        assertThat(SetupState.NOT_STARTED.advanceTo(SetupState.ADMIN_CREATED))
            .isEqualTo(SetupState.ADMIN_CREATED)
        assertThatThrownBy { SetupState.NOT_STARTED.advanceTo(SetupState.SHARED_KEYS_DONE) }
            .isInstanceOf(IllegalSetupTransitionException::class.java)
        assertThatThrownBy { SetupState.ADMIN_CREATED.advanceTo(SetupState.NOT_STARTED) }
            .isInstanceOf(IllegalSetupTransitionException::class.java)
        assertThatThrownBy { SetupState.COMPLETE.next() }
            .isInstanceOf(IllegalSetupTransitionException::class.java)
    }

    @Test
    @DisplayName("최소 상태·정확한 상태 요구")
    fun requirements() {
        SetupState.TOSS_DECIDED.requireAtLeast(SetupState.ADMIN_CREATED)
        assertThatThrownBy { SetupState.NOT_STARTED.requireAtLeast(SetupState.ADMIN_CREATED) }
            .isInstanceOf(IllegalSetupTransitionException::class.java)
        assertThatThrownBy { SetupState.ADMIN_CREATED.requireExactly(SetupState.NOT_STARTED) }
            .isInstanceOf(IllegalSetupTransitionException::class.java)
        assertThat(SetupState.COMPLETE.isComplete).isTrue()
    }
}
