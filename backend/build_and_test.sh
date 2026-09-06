#!/usr/bin/env bash
# 离线密码本 · 后端契约测试构建脚本（Git Bash / Linux）
# 纯 JVM 执行：用 K2JVMCompiler 编译纯模块 + tests/contract，用 sqlite-jdbc 跑机器可校验契约测试。
# 不依赖 Android SDK；Android 胶水类（DAO/UnlockManager/BiometricKeystore）仅在 Android/Gradle 编译。
#
# 说明：JVM 只认 Windows 路径形式（C:/...），而 Git Bash 用 /c/...。故 cp/mkdir 用 /c/ 形式，
# 传给 java/K2JVMCompiler 的路径统一转成 C:/ 形式。规范源码仍以 backend/src 与 backend/tests 为准
# （复制到无中文临时工作目录仅为本次 JVM 验证，不修改规范源码）。
set -e

# 工具链目录（需含 libs/ 下的 kotlin-compiler 等 jar；可选 jdk/）。
# 外部贡献者用环境变量覆盖：TOOLCHAIN_DIR=/path/to/toolchain（Windows Git Bash 下传 C:/... 形式）。
TOOLCHAIN="${TOOLCHAIN_DIR:-C:/Users/Will/.workbuddy/toolchain}"
to_unix() { if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else echo "$1"; fi; }
TOOLCHAIN_UNIX="$(to_unix "$TOOLCHAIN")"
# JDK：优先环境变量 JAVA_HOME，否则用工具链内置 JDK17。
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA_HOME_UNIX="$(to_unix "$JAVA_HOME")"
else
    JAVA_HOME_UNIX="$TOOLCHAIN_UNIX/jdk/jdk-17.0.12+7"
fi
export PATH="$JAVA_HOME_UNIX/bin:$PATH"
JAVA="java"
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/src/vault"
# 工作目录（需无中文/空格，规避 JVM 路径编码差异）；可用 WORK_DIR 覆盖（Windows 下传 C:/... 形式）。
WORK_WIN="${WORK_DIR:-C:/Users/Will/.workbuddy/vault_work}"
WORK_UNIX="$(to_unix "$WORK_WIN")"
mkdir -p "$WORK_UNIX"

# 复制纯源码到无中文工作目录（规避 JVM 中文路径/编码差异）。
cp "$SRC/Assert.kt" "$SRC/Json.kt" "$SRC/models.kt" "$SRC/VaultCrypto.kt" \
   "$SRC/VaultFormat.kt" "$SRC/VaultMerge.kt" "$SRC/SqlBuilders.kt" \
   "$SRC/Schema.kt" "$SRC/VaultSession.kt" \
   "$ROOT/tests/contract/ContractTests.kt" "$WORK_UNIX/"

# 显式列出 jar（避免 K2JVMCompiler 把 libs/* 通配误当源码）。
COMPILER_CP="$TOOLCHAIN/libs/kotlin-compiler-2.0.21.jar;$TOOLCHAIN/libs/kotlin-stdlib-2.0.21.jar;$TOOLCHAIN/libs/kotlin-reflect-2.0.21.jar;$TOOLCHAIN/libs/kotlin-script-runtime-2.0.21.jar;$TOOLCHAIN/libs/trove4j-1.0.20181211.jar;$TOOLCHAIN/libs/annotations-13.0.jar;$TOOLCHAIN/libs/kotlinx-coroutines-core-jvm-1.8.1.jar"
# sqlite-jdbc 3.46 运行时依赖 slf4j-api（仅测试用；生产用 Android 内置 SQLite，不涉及）。
# 2026-09-02 #QA-024 方案 A：KDF 换 Bouncy Castle 纯 Java（argon2-jvm/jna 原生库无 Android ABI，已弃用）。
PROGRAM_CP="$TOOLCHAIN/libs/bcprov-jdk15to18-1.78.1.jar;$TOOLCHAIN/libs/sqlite-jdbc-3.46.0.0.jar;$TOOLCHAIN/libs/slf4j-api-2.0.16.jar;$TOOLCHAIN/libs/kotlin-stdlib-2.0.21.jar"

# 直接 java 调 K2JVMCompiler 时 -include-runtime 找不到发行版 lib（它按发行版 home 定位），
# 故去掉；运行时 kotlin-stdlib 已在 PROGRAM_CP 中提供。
"$JAVA" -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib \
  -cp "$PROGRAM_CP" \
  -d "$WORK_WIN/out/contract.jar" \
  "$WORK_WIN/Assert.kt" \
  "$WORK_WIN/Json.kt" \
  "$WORK_WIN/models.kt" \
  "$WORK_WIN/VaultCrypto.kt" \
  "$WORK_WIN/VaultFormat.kt" \
  "$WORK_WIN/VaultMerge.kt" \
  "$WORK_WIN/SqlBuilders.kt" \
  "$WORK_WIN/Schema.kt" \
  "$WORK_WIN/VaultSession.kt" \
  "$WORK_WIN/ContractTests.kt"

"$JAVA" -cp "$WORK_WIN/out/contract.jar;$PROGRAM_CP" vault.ContractTestsKt
