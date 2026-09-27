plugins { `kotlin-dsl` }

repositories { mavenCentral() }

dependencies {
    // Spotless 사용자 정의 단계(FormatterFunc)를 구현하기 위한 라이브러리. 플러그인과 같은 버전이어야 함
    implementation("com.diffplug.spotless:spotless-lib:4.10.2")
    // buildSrc 클래스로더가 플러그인의 lib 를 가리므로 extra 도 같은 자리에 둬야 줄 끝 정책(GIT_ATTRIBUTES)이 로드됨
    implementation("com.diffplug.spotless:spotless-lib-extra:4.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
