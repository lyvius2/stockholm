package banghak.stock.architecture

import banghak.stock.shared.config.RuntimeProfiles
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** engine·relay의 빈은 해당 프로필에서만 로드됨. 프로필 없는 빈은 shared에만 둠. */
class ProfileBeansTest {
    @Test
    @DisplayName("engine의 모든 빈 클래스는 @Profile(\"engine\")을 가짐")
    fun engineBeansAreRestrictedToEngineProfile() {
        beansIn("banghak.stock.engine..")
            .should(beRestrictedTo(RuntimeProfiles.ENGINE))
            .check(ProductionClasses.all)
    }

    @Test
    @DisplayName("relay의 모든 빈 클래스는 @Profile(\"relay\")을 가짐")
    fun relayBeansAreRestrictedToRelayProfile() {
        beansIn("banghak.stock.relay..")
            .should(beRestrictedTo(RuntimeProfiles.RELAY))
            .check(ProductionClasses.all)
    }

    @Test
    @DisplayName("shared의 빈은 프로필을 갖지 않음")
    fun sharedBeansHaveNoProfile() {
        beansIn("banghak.stock.shared..")
            .should()
            .notBeAnnotatedWith(Profile::class.java)
            .check(ProductionClasses.all)
    }

    private fun beansIn(packageIdentifier: String) =
        classes()
            .that()
            .resideInAPackage(packageIdentifier)
            .and()
            .areMetaAnnotatedWith(Component::class.java)

    private fun beRestrictedTo(profile: String) =
        object : ArchCondition<JavaClass>("be annotated with @Profile(\"$profile\")") {
            override fun check(item: JavaClass, events: ConditionEvents) {
                val declared =
                    if (item.isAnnotatedWith(Profile::class.java))
                        item.getAnnotationOfType(Profile::class.java).value.toList()
                    else emptyList()
                val satisfied = declared == listOf(profile)
                events.add(
                    SimpleConditionEvent(item, satisfied, "${item.name} 의 @Profile 값: $declared")
                )
            }
        }
}
