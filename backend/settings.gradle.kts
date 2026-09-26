plugins {
    // CI처럼 GraalVM JDK 25가 없는 환경에서 toolchain을 자동으로 내려받음
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "stockholm"
