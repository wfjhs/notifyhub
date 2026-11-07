#!/usr/bin/env bash
# 命令行构建包装：确保用 JDK 17+ 驱动 Maven。
#
# 为什么需要它：本机系统默认 java 指向 1.8，直接跑 mvn 会报
#   UnsupportedClassVersionError (class file version 61.0)
# 这个脚本只负责挑一个合适的 JDK 设进 JAVA_HOME，然后把参数原样交给 mvn。
#
# JDK 查找顺序：MVN_JAVA_HOME → JAVA_HOME(需 ≥17) → 常见 JDK 安装位置 → PATH 里的 java(需 ≥17)
# 这样别人 clone 下来只要装了 JDK 21（或设了 JAVA_HOME）就能直接跑。
set -euo pipefail

java_major() {
  "$1" -version 2>&1 | head -n1 \
    | sed -E 's/.*version "([0-9]+)(\.([0-9]+))?.*/\1/' || echo 0
}

pick_java_home() {
  local cand
  for cand in \
    "${MVN_JAVA_HOME:-}" \
    "${JAVA_HOME:-}" \
    "${HOME:-/c/Users/$USER}/.jdks/openjdk-21.0.2" \
    "/usr/lib/jvm/java-21-openjdk-amd64" \
    "/usr/lib/jvm/java-17-openjdk-amd64"; do
    [ -n "$cand" ] && [ -x "$cand/bin/java" ] || continue
    if [ "$(java_major "$cand/bin/java")" -ge 17 ] 2>/dev/null; then
      echo "$cand"
      return 0
    fi
  done

  # 兜底：PATH 里的 java 也可能是 17+
  local p
  p="$(command -v java 2>/dev/null || true)"
  if [ -n "$p" ] && [ "$(java_major "$p")" -ge 17 ] 2>/dev/null; then
    echo "$(cd "$(dirname "$p")/.." && pwd)"
    return 0
  fi
  return 1
}

if ! PICKED="$(pick_java_home)"; then
  echo "[mvn.sh] 找不到 JDK 17+，请设置 JAVA_HOME 或 MVN_JAVA_HOME 指向 JDK 21" >&2
  exit 1
fi
export JAVA_HOME="$PICKED"

# 优先用同名 .cmd（Windows 原生脚本）。
# 直接用 POSIX 版 `mvn` 在 git bash 里会因为路径转换问题找不到 plexus-classworlds，报
#   ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher
# 但 .cmd 由 cmd.exe 解释，不认 /c/... 这种 MSYS 路径，所以 JAVA_HOME 要先转成 Windows 风格。
if command -v mvn >/dev/null 2>&1; then
  MVN_BIN="$(command -v mvn)"
  if [ -f "${MVN_BIN}.cmd" ]; then
    if command -v cygpath >/dev/null 2>&1; then
      export JAVA_HOME="$(cygpath -w "$PICKED")"
    fi
    exec "${MVN_BIN}.cmd" "$@"
  fi
  exec "$MVN_BIN" "$@"
fi
if [ -n "${MAVEN_HOME:-}" ] && [ -x "$MAVEN_HOME/bin/mvn" ]; then
  exec "$MAVEN_HOME/bin/mvn" "$@"
fi

echo "[mvn.sh] 找不到 Maven，请安装 mvn 或设置 MAVEN_HOME" >&2
exit 1
