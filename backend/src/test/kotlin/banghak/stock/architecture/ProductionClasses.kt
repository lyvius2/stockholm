package banghak.stock.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption

/** 경계 검증 테스트가 공유하는 프로덕션 클래스 집합. 테스트 코드는 제외함. */
object ProductionClasses {
    const val ROOT = "banghak.stock"

    val all: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .withImportOption(ImportOption.DoNotIncludeJars())
            .importPackages(ROOT)
}
