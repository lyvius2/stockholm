#!/usr/bin/env bash
# bootJar → jdeps 로 필요한 JDK 모듈 산출 → jlink 경량 JRE → desktop/resources/daemon → electron-builder dmg (arm64).
# 서명·공증은 하지 않음(배포 시점에 결정).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DAEMON_RES="$ROOT/desktop/resources/daemon"
JAVA_HOME_FOR_JLINK="${JAVA_HOME:-$(/usr/libexec/java_home -v 25 2>/dev/null || echo "$(dirname "$(dirname "$(readlink -f "$(which java)")")")")}"
WORK="$(mktemp -d -t stockholm-dist)"
trap 'rm -rf "$WORK"' EXIT

# jdeps 가 놓치기 쉬운 런타임 모듈(리플렉션·JNDI·로케일·로깅 브리지)
EXTRA_MODULES="java.naming,java.logging,jdk.localedata,jdk.charsets"

echo "1/5 bootJar"
(cd "$ROOT/backend" && ./gradlew bootJar --quiet)
JAR="$(ls "$ROOT/backend/build/libs/"stockholm-*.jar | grep -v plain | head -1)"

echo "2/5 jdeps (필요한 JDK 모듈)"
(cd "$WORK" && unzip -q "$JAR")
MODULES="$(cd "$WORK" && "$JAVA_HOME_FOR_JLINK/bin/jdeps" --multi-release 25 --ignore-missing-deps -R --print-module-deps \
  --class-path 'BOOT-INF/lib/*' BOOT-INF/classes)"
echo "   jdeps: $MODULES"

echo "3/5 jlink ($JAVA_HOME_FOR_JLINK)"
mkdir -p "$DAEMON_RES"; rm -rf "$DAEMON_RES/jre" "$DAEMON_RES/stockholm.jar"
"$JAVA_HOME_FOR_JLINK/bin/jlink" \
  --add-modules "$MODULES,$EXTRA_MODULES" \
  --strip-debug --no-man-pages --no-header-files --compress zip-6 \
  --output "$DAEMON_RES/jre"
cp "$JAR" "$DAEMON_RES/stockholm.jar"
echo "   jre: $(du -sh "$DAEMON_RES/jre" | cut -f1), jar: $(du -sh "$DAEMON_RES/stockholm.jar" | cut -f1)"

echo "4/5 desktop build"
npm --prefix "$ROOT/desktop" run generate --silent
npm --prefix "$ROOT/desktop" run build --silent

echo "5/5 dmg"
(cd "$ROOT/desktop" && npx electron-builder --mac --arm64 --config electron-builder.yml)
echo "done: $(ls "$ROOT/desktop/release/"*.dmg)"
