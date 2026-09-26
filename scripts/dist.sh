#!/usr/bin/env bash
# bootJar → jlink 경량 JRE → desktop/resources/daemon → electron-builder dmg (arm64).
# 서명·공증은 하지 않음(배포 시점에 결정).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DAEMON_RES="$ROOT/desktop/resources/daemon"
JAVA_HOME_FOR_JLINK="${JAVA_HOME:-$(/usr/libexec/java_home -v 25)}"

echo "1/4 bootJar"
(cd "$ROOT/backend" && ./gradlew bootJar --quiet)
JAR="$(ls "$ROOT/backend/build/libs/"*.jar | grep -v plain | head -1)"

echo "2/4 jlink (GraalVM JDK 25)"
rm -rf "$DAEMON_RES"; mkdir -p "$DAEMON_RES"
# TODO(1단계 11번): jdeps 로 실제 필요한 모듈 목록을 뽑아 --add-modules 를 좁힘
"$JAVA_HOME_FOR_JLINK/bin/jlink" \
  --add-modules java.base,java.logging,java.sql,java.naming,java.desktop,java.management,java.instrument,java.net.http,java.security.jgss,jdk.unsupported,jdk.crypto.ec \
  --strip-debug --no-man-pages --no-header-files --compress zip-6 \
  --output "$DAEMON_RES/jre"
cp "$JAR" "$DAEMON_RES/stockholm.jar"

echo "3/4 desktop build"
npm --prefix "$ROOT/desktop" run build

echo "4/4 dmg"
(cd "$ROOT/desktop" && npx electron-builder --mac --arm64 --config electron-builder.yml)
echo "done: $ROOT/desktop/release"
