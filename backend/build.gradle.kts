import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spotless)
}

group = "banghak"

version = "0.1.0-SNAPSHOT"

description = "Stockholm — 가족용 주식 매매 지원 데몬(engine)과 중계 서버(relay)"

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.GRAAL_VM
    }
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        // JSR-305 애너테이션을 엄격하게 해석해 Spring API의 null 안전성을 Kotlin 타입으로 받음
        freeCompilerArgs.add("-Xjsr305=strict")
        allWarningsAsErrors = true
    }
}

repositories { mavenCentral() }

// JPA 엔티티는 Hibernate 프록시를 위해 open 이어야 함
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

// protocol/generate.sh 가 JSON Schema에서 만든 Kotlin 타입. 커밋하지 않음
kotlin.sourceSets.main { kotlin.srcDir(layout.buildDirectory.dir("generated/protocol/kotlin")) }

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.jackson2.bom))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.aspectj)
    implementation(libs.spring.boot.starter.cache)
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.caffeine)
    implementation(libs.resilience4j.spring.boot4)
    implementation(libs.spring.boot.starter.jpa)
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.hibernate.community.dialects)
    implementation(libs.sqlite.jdbc)
    implementation(libs.spring.security.crypto)
    implementation(libs.bouncycastle)
    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.jackson)
    implementation(libs.jackson2.module.kotlin)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.wiremock)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    // JDK 25에서 Mockito 등 동적 에이전트 붙일 때 경고를 오류로 올리지 않음
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

tasks.test {
    // 학습 테스트는 외부 API를 부르므로 기본 빌드에서 제외함. scripts/learning-tests.sh로만 실행함
    useJUnitPlatform { excludeTags("learning") }
}

tasks.register<Test>("learningTest") {
    description = "외부 API 학습 테스트만 실행함(키는 환경 변수, 주문 엔드포인트 호출 금지)"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // withType<Test> 블록에서 excludeTags 를 걸면 이 task 에도 붙어 학습 테스트가 0건이 됨
    useJUnitPlatform { includeTags("learning") }
    // 외부 서버 상태가 바뀔 수 있으므로 캐시된 결과를 쓰지 않고 매번 실행함
    outputs.upToDateWhen { false }
    shouldRunAfter(tasks.test)
}

tasks.bootRun {
    // 상주 메모리 목표 400MB 이하. 1단계에서 실측함
    jvmArgs("-Xmx384m")
}

/** 데몬(engine 프로필)과 Electron 개발 서버를 한 명령으로 띄움. 둘 다 Gradle이 관리하는 프로세스라 Ctrl+C 로 함께 내려감. */
abstract class DevTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Internal abstract val desktopDir: DirectoryProperty
    @get:Internal abstract val runtimeClasspath: ConfigurableFileCollection
    @get:Internal abstract val mainClass: Property<String>

    @TaskAction
    fun run() {
        val npm = NpmLocator.locate()
        val electron = Thread {
            execOperations.exec {
                workingDir = desktopDir.get().asFile
                // npm 스크립트가 `node` 를 찾을 수 있게 같은 폴더를 PATH 앞에 둠
                environment(
                    "PATH",
                    npm.parentFile.absolutePath + File.pathSeparator + System.getenv("PATH"),
                )
                commandLine(npm.absolutePath, "run", "dev")
            }
        }
        electron.isDaemon = true
        electron.start()
        execOperations.javaexec {
            classpath = runtimeClasspath
            mainClass.set(this@DevTask.mainClass)
            args("--spring.profiles.active=engine")
            jvmArgs("-Xmx384m")
        }
    }
}

/**
 * npm 실행 파일을 찾음. IDE 나 launchd 에서 띄운 Gradle 은 셸의 `.zshrc` 를 거치지 않아 nvm 이 넣어 준 PATH 가 없음. 순서: 환경 변수
 * STOCKHOLM_NPM → PATH → nvm(가장 높은 버전) → volta → Homebrew → /usr/local.
 */
object NpmLocator {
    fun locate(): File {
        val candidates = buildList {
            System.getenv("STOCKHOLM_NPM")?.let { add(File(it)) }
            addAll(onPath())
            addAll(nvmInstalls())
            val home = File(System.getProperty("user.home"))
            add(File(home, ".volta/bin/npm"))
            add(File("/opt/homebrew/bin/npm"))
            add(File("/usr/local/bin/npm"))
        }
        return candidates.firstOrNull { it.isFile && it.canExecute() }
            ?: throw GradleException(
                "npm 을 찾지 못함. 찾아본 곳: " +
                    candidates.joinToString() +
                    ". STOCKHOLM_NPM 환경 변수로 npm 경로를 지정하면 됨"
            )
    }

    private fun onPath(): List<File> =
        (System.getenv("PATH") ?: "")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it, "npm") }

    private fun nvmInstalls(): List<File> {
        val versions = File(System.getProperty("user.home"), ".nvm/versions/node")
        return (versions.listFiles() ?: emptyArray())
            .sortedByDescending { versionOrder(it.name) }
            .map { File(it, "bin/npm") }
    }

    /** `v22.17.1` 같은 폴더 이름을 정렬 가능한 수로 바꿈. 각 자리는 1000 미만으로 봄 */
    private fun versionOrder(name: String): Long =
        name.removePrefix("v").split('.').take(3).fold(0L) { acc, part ->
            acc * 1000 + (part.toLongOrNull() ?: 0)
        }
}

tasks.register<DevTask>("dev") {
    description = "데몬(engine) + Electron 개발 실행"
    group = "application"
    dependsOn(tasks.classes)
    desktopDir.set(layout.projectDirectory.dir("../desktop"))
    runtimeClasspath.from(sourceSets.main.get().runtimeClasspath)
    mainClass.set("banghak.stock.StockholmApplicationKt")
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
}
