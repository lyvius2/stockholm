package banghak.stock.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaParameterizedType
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 사용자 데이터를 담는 표의 리포지터리는 모든 질의가 `userId`로 범위를 한정함(절대 규칙 8). 대상은 엔티티에 `userId` 필드가 있는 리포지터리이고, 설치 단위
 * 표(종목 마스터·달력 등)는 대상이 아님. 이 규칙은 이름·JPQL 문자열만 보므로 "빠뜨림"을 잡는 그물임. 조건이 실제로 사용자 범위를 가르는지는 영속성 통합 테스트(다른
 * 사용자 데이터 미조회)가 검증함.
 */
class RepositoryUserScopedTest {
    @Test
    @DisplayName("userId 필드를 가진 엔티티의 리포지터리는 모든 선언 메서드가 userId로 한정됨")
    fun userScopedRepositoriesQueryByUserId() {
        classes()
            .that()
            .resideInAPackage(REPOSITORY_PACKAGE)
            .and()
            .areInterfaces()
            .should(haveAllQueriesScopedByUserId())
            .check(ProductionClasses.all)
    }

    private fun haveAllQueriesScopedByUserId() =
        object :
            ArchCondition<JavaClass>(
                "declare only queries scoped by userId when the entity has userId"
            ) {
            override fun check(item: JavaClass, events: ConditionEvents) {
                val entity = entityOf(item) ?: return
                val userIdField = entity.tryGetField(USER_ID_FIELD).orElse(null) ?: return
                // 사용자 등록부(app_user)는 userId 가 곧 PK 라 목록 조회가 정당함
                if (userIdField.isAnnotatedWith(ID_ANNOTATION)) return
                item.methods
                    .filterNot { isScopedByUserId(it) }
                    .forEach {
                        events.add(
                            SimpleConditionEvent.violated(it, "${it.fullName} 이 userId 없이 질의함")
                        )
                    }
            }
        }

    private fun entityOf(repository: JavaClass): JavaClass? =
        repository.interfaces
            .filterIsInstance<JavaParameterizedType>()
            .filter { it.toErasure().name.startsWith(SPRING_DATA_PACKAGE) }
            .mapNotNull { it.actualTypeArguments.firstOrNull()?.toErasure() }
            .firstOrNull()

    private fun isScopedByUserId(method: JavaMethod): Boolean {
        val derivedName = method.name.contains(USER_ID_IN_NAME)
        val query = method.annotations.firstOrNull { it.rawType.name == QUERY_ANNOTATION }
        val jpql =
            query?.tryGetExplicitlyDeclaredProperty("value")?.orElse(null)?.toString().orEmpty()
        // "userId is null" 은 설치 단위(공유) 행만 고르는 명시적 조건임
        return derivedName || jpql.contains(USER_ID_PARAM) || jpql.contains(SHARED_ONLY)
    }

    companion object {
        private const val REPOSITORY_PACKAGE = "..adapter.out.persistence.repository.."
        private const val SPRING_DATA_PACKAGE = "org.springframework.data."
        private const val QUERY_ANNOTATION = "org.springframework.data.jpa.repository.Query"
        private const val USER_ID_FIELD = "userId"
        private const val USER_ID_IN_NAME = "UserId"
        private const val USER_ID_PARAM = ":userId"
        private const val SHARED_ONLY = "userId is null"
        private const val ID_ANNOTATION = "jakarta.persistence.Id"
    }
}
