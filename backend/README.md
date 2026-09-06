# 离线密码本 · 数据/加密层（backend/）

纯离线 Android 原生密码管理器的**本地数据访问 + 加密层**实现。无后端、无网络（契约 §1）。
本目录交付 Kotlin 源码（Android min API 26）+ 机器可校验的契约测试套件。

- 唯一事实源：`api-contract.md`（Phase 3 冻结契约）、`schema.sql`（DDL）。
- 纪律：零抽象（具体类，无 interface/Factory/泛型仓储）、决策阶梯自上而下、副作用隔离、自检即文档（`assert`）、`ponytail` 留痕。

---

## 1. 模块结构

```
backend/
├── src/vault/
│   ├── Assert.kt            # 自检辅助（checkThat / assertBytesEq），无测试框架
│   ├── Json.kt              # 手写 JSON 编解码（固定子集，无 kotlinx.serialization）
│   ├── models.kt            # SortKey / *Row / *Input / *Report / SecretPlain / 异常
│   ├── VaultCrypto.kt       # 加密原语：Argon2id / AES-256-GCM / wrap / verifier（纯）
│   ├── VaultFormat.kt       # .vault 二进制格式 序列化/解析（纯）
│   ├── VaultMerge.kt        # 导入合并决策 + 荷载编解码（纯）
│   ├── SqlBuilders.kt       # 列表/分类查询 SQL 构造（纯，单一事实源）
│   ├── Schema.kt            # DDL 单一事实源（纯，Android 与 JVM 测试共用）
│   ├── VaultSession.kt      # 解锁会话 DEK 内存持有（纯，可在 JVM 测试）
│   ├── VaultDbHelper.kt     # SQLiteOpenHelper，执行 Schema.kt 的 DDL（Android）
│   ├── PasswordEntryDao.kt  # 密码条目 DAO（Android）
│   ├── CategoryDao.kt       # 分类 DAO（含条目计数/不可删规则）（Android）
│   ├── AppSettingsStore.kt  # 设置 + 密钥材料读取（Android）
│   ├── VaultBackup.kt       # 导出/导入合并（Android）
│   ├── BiometricKeystore.kt # Android Keystore 生物识别通道（Android，需 Keystore）
│   ├── UnlockManager.kt     # 解锁/锁定会话（Android，组合 Session+Settings+Biometric）
│   └── RealVaultData.kt     # VaultData 接口真实实现，桥接前端 UI 与 vault.* DAO（Android，同编译进 frontend app module）
├── tests/contract/
│   └── ContractTests.kt     # 机器可校验契约测试（纯 JVM + sqlite-jdbc，无框架）
└── build_and_test.sh        # 本地 JVM 契约测试构建脚本
```

**副作用隔离**（v1.3 铁律）：纯模块（`Assert/Json/models/VaultCrypto/VaultFormat/VaultMerge/SqlBuilders/Schema/VaultSession`）
零 Android 依赖，可在普通 JVM 直接编译测试；Android 胶水类（`*Dao/AppSettingsStore/VaultBackup/VaultDbHelper/BiometricKeystore/UnlockManager`）
仅在 Android/Gradle 编译。纯逻辑（加密/合并/SQL 构造）与 DB/文件/Keystore 副作用在函数级分离。

---

## 2. 加密规范（契约 §4，冻结）

| 项 | 取值 |
|---|---|
| KDF | Argon2id，`m=65536KB / t=3 / p=1`，盐 16 字节随机，输出 32 字节 KEK |
| KEK/DEK | KEK 由主密码派生，**不落盘**；DEK 32 字节随机，仅驻内存；改主密码仅重包 DEK，条目 secret_blob 不变 |
| 对称加密 | AES-256-GCM，每条 secret_blob / 每个 wrapped_* / 每次导出 独立随机 12 字节 nonce，GCM tag 16 字节 |
| secret_blob 明文 | JSON `{password, website, notes}`，整体加密 |
| .vault 文件 | magic `VLT1` + version + kdf_algo + kdf_params + export_salt + nonce + ciphertext+tag；GCM 认证失败 = WRONG_PASSWORD |
| 生物识别 | Android Keystore AES-256-GCM，`setUserAuthenticationRequired(true)`，无 PIN 备援；包裹 DEK → `wrapped_dek_biometric` |

**四条底线**：信任边界输入校验（导出/导入解析失败显式抛 `VaultException`）；不静默失败（GCM 认证失败必抛，不返回错误明文）；密钥材料绝不落盘明文（DEK 内存态、lock 清零；KEK/盐经 Keystore/主密码保护）；IDOR 不适用（本地单用户，无多租户，记录于契约 §7）。

---

## 3. 依赖（决策阶梯）

| 依赖 | 用途 | 选型理由（决策阶梯） | 范围 |
|---|---|---|---|
| `javax.crypto` (JDK/Android 标准库) | AES-256-GCM | 标准库优先，零新增依赖 | 生产+测试 |
| `de.mkammerer:argon2-jvm:2.7` + `net.java.dev.jna:jna:5.14.0` | Argon2id KDF | KDF **不在** JDK/Android 标准库；按契约 §4.2 "避免自研"，采用成熟纯 JVM 库（同时兼容 Android）；`rawHash` 直接取 32 字节密钥 | 生产（Android 通过 Gradle 引入） |
| `org.xerial:sqlite-jdbc:3.46.0.0` | JVM 契约测试的内存 SQLite | **仅测试**；生产用 Android 内置 SQLite，不引入 | 仅测试 |
| `org.slf4j:slf4j-api:2.0.16` | sqlite-jdbc 运行期日志门面 | **仅测试**运行期；无绑定走 NOP，无业务影响 | 仅测试 |

> 标准库优先说明：除 Argon2id KDF（标准库无）必须引入 `argon2-jvm` 外，其余均优先使用 JDK/Android 标准库
> （AES-GCM、JSON 手写、SQLite 用平台内置）。`kotlinx.serialization` 等未引入（JSON 结构固定，手写即可）。
> 跨工具字节级互导 `.vault` 时需核对 argon2-jvm 默认 `v=19` 是否被对端支持（见 `VaultCrypto.kt` 顶部 ponytail）。

---

## 4. 构建与运行

### 4.1 生产（Android）
标准 Android/Gradle 工程：把 `src/vault/` 作为源码集加入模块，`build.gradle` 引入 `argon2-jvm:2.7` + `jna:5.14.0`。
所有 `android.*` 导入（DAO/Helper/Keystore/UnlockManager）由 Android SDK 提供。`Schema.kt` 的 `VAULT_SCHEMA_STATEMENTS`
由 `VaultDbHelper.onCreate` 执行。

#### 4.1.1 接入前端（RealVaultData ↔ UI 边界）
`RealVaultData`（package `com.qiqiao.passwordvault.data`）实现前端 `VaultData` 接口（同包，`implements` 零改动），
桥接 UI 与 `vault.*` DAO。它是**新增文件**，不进入 `tests/contract` 的 JVM 编译集，契约测试保持绿色。

接入步骤（frontend app module）：
1. **源码集引入**：把 `backend/src/vault/`（含 `RealVaultData.kt` 与全部 `*Dao/AppSettingsStore/VaultBackup/UnlockManager/VaultDbHelper/BiometricKeystore`）
   作为 `app` module 的源码集或 library module 引入，使 `com.qiqiao.passwordvault.data` 与 `vault` **同编译单元**
   （`RealVaultData` 需要同时见到 `VaultData`/`model.*` 与 `vault.*`）。
2. **依赖**：`app/build.gradle` 引入 `argon2-jvm:2.7` + `jna:5.14.0`（加密 KDF，标准库无）。
3. **注入**：在 `MainApplication.onCreate` 把 `Vault.data = MockVaultData()` 改为 `Vault.data = RealVaultData(applicationContext)`。
   UI 层通过 `Vault.data.xxx(...)` 调用，**代码零改动**。
4. **解锁流**：`isInitialized / initializeMasterPassword / unlockWithPassword / unlockWithBiometric` 映射到 `AppSettingsStore`/`UnlockManager`；
   `lock/isLocked/getActiveDek` 由 `UnlockManager` 提供（接口当前只用前 4 个解锁方法）。
5. **备份文件名**：`exportVault` 调 `VaultBackup.exportVault` 拿 `.vault` 字节 → 写入 `Context.getFilesDir()` → 回调文件名 `vault_export_<epochMillis>.vault`；
   `importVault` 两端都收 `ByteArray`，一致。

> 类型映射边界：后端 `PasswordEntryRow.password/website/notes/categoryName` 为 `String?`，前端为 `String`（默认 `""`）；
> `RealVaultData` 映射时把后端 `null` → 前端 `""`（见 §6 ponytail 与 `realVaultDataSelfTest` 自检）。其余模型字段（EntryInput/CategoryRow/AppSettings/SettingsPatch/MergeReport/SortKey）完全一致。

> **接线状态（已就位，非仅文档）**：上述三步已在 `frontend/app` 实际落地 ——
> 1. **源码集引入（采用 srcDir 指向 backend/src/vault 方式，单一事实源不复制）**：`frontend/app/build.gradle.kts` 的 `android.sourceSets["main"].java.srcDirs += file("${projectDir}/../../backend/src/vault")`，使 `com.qiqiao.passwordvault.data`（RealVaultData）与 `vault`（DAO/加密）同编译单元；Kotlin 不强制"目录=包"，故直接指目录即可。
> 2. **依赖**：`frontend/app/build.gradle.kts` 的 `dependencies` 已追加 `de.mkammerer:argon2-jvm:2.7` 与 `net.java.dev.jna:jna:5.14.0`（未删任何现有 AndroidX 依赖）。
> 3. **注入**：`frontend/app/.../MainApplication.kt` 已改为 `import com.qiqiao.passwordvault.data.RealVaultData` + `Vault.data = RealVaultData(applicationContext)`（原 Mock 注释/导入已移除；MockVaultData 文件保留作 debug flavor 兜底）。
>
> **未经本环境 Android 编译**：本环境无 Android SDK，无法本地 `assembleDebug` 验证；需真机/模拟器执行 `./gradlew assembleDebug` 确认编译通过与运行。

### 4.2 契约测试（纯 JVM，无 Android SDK）
`tests/contract/` 用 `main` + `assert` 直接执行，不依赖 JUnit。它用**与 DAO 相同的 SQL**（`SqlBuilders` 生成的读取 SQL / DAO 写入 SQL 的逐字副本）
+ **相同的加密原语**（`VaultCrypto`）在 `sqlite-jdbc` 上重放，逐条验证契约方法的可观察行为（见 §5）。

本地脚本 `build_and_test.sh`（Git Bash）会：把纯源码复制到无中文的临时工作目录（规避 JVM 中文路径/编码差异），
用 `K2JVMCompiler` 编译，再用 `sqlite-jdbc` 运行。需要先准备工具链：
- JDK 17（如 `adoptium-jdk-17`）
- Kotlin 编译器 2.0.21（`kotlin-compiler` jar + 依赖）
- `argon2-jvm:2.7`、`jna:5.14.0`、`sqlite-jdbc:3.46.0.0`、`slf4j-api:2.0.16`、`kotlin-stdlib` 等 jar

脚本顶部 `TOOLCHAIN` 指向本地工具链目录（含 `jdk/`、`kotlinc/kotlinc/bin/kotlinc` 启动器、`libs/`），
请按本机路径修改（或改用 `./gradlew` 等已有构建链）。运行：

```bash
cd backend
bash build_and_test.sh
# 期望输出：ALL CONTRACT CHECKS OK
```

> 环境注意（仅影响本本地脚本，不影响规范源码）：
> - JVM 只认 Windows 路径形式 `C:/...`，Git Bash 的 `/c/...` 不被 JVM 解析；脚本已统一用 `C:/`。
> - `sqlite-jdbc` 运行期需 `slf4j-api`（无绑定走 NOP），仅测试用，生产不涉及。
> - `-include-runtime` 在直接 `java -cp` 调 `K2JVMCompiler` 时不可用（找不到发行版 lib），运行期 `kotlin-stdlib` 已由 classpath 提供。

---

## 5. 契约测试覆盖（逐条对应 api-contract.md）

`main` 先跑纯模块自检（`*SelfTest`），再逐条检查（每条用独立内存库，避免 `categories.name` UNIQUE 冲突）：

| 契约方法 | 校验点 |
|---|---|
| `PasswordEntryDao.listEntries` | 掩码（不解密 secret_blob）；name/username LIKE 搜索；NAME_ASC/DESC；CATEGORY_ASC（按 category_id）；分类过滤；分页 limit/offset |
| `PasswordEntryDao.getEntry` | 解密回填 password/website/notes；非活跃 DEK 解密必抛（模拟锁定拒绝） |
| `PasswordEntryDao.createEntry` | secret_blob 密文 ≠ 明文（含 GCM tag，长度 > 明文） |
| `PasswordEntryDao.updateEntry` | 重加密后密码更新 |
| `PasswordEntryDao.deleteEntry` | 软删（is_deleted=1），列表不再返回 |
| `CategoryDao.listCategories` | 单条 LEFT JOIN 取 entryCount（防 N+1） |
| `CategoryDao.deleteCategory` | "其他" 种子不可删；非空分类不可删；清空后可删 |
| `AppSettingsStore.changeMasterPassword` | 旧密码解锁失败；新密码解锁成功且 **DEK 不变**；secret_blob 字节不变；新密码仍可解密原条目 |
| `VaultBackup.exportVault/importVault` | 往返一致；分类/条目合并新增正确；**错误密码 → `WrongPasswordException`**；两次导出 nonce 不同（文件不同） |
| `VaultCrypto` / `VaultFormat` / `VaultMerge` / `Json` | 派生确定性、AES-GCM 往返、nonce 不复用、wrap/unwrap、verifier、篡改必抛、.vault 往返、合并决策、JSON 转义 |
| `VaultSession` | 解锁/锁定/`getActiveDek`/`isLocked` 与 lock 清零 |

**自检即文档**（`assert` 静默自检，无测试框架）：`vaultCryptoSelfTest` / `vaultFormatSelfTest` / `vaultMergeSelfTest` / `jsonSelfTest` / `vaultSessionSelfTest`，
均在 `ContractTests.kt` 的 `main` 开头执行，失败即抛 `AssertionError` 中断。

---

## 6. ponytail 留痕清单

| 位置 | 简化 | 触发升级阈值 |
|---|---|---|
| `VaultCrypto.kt` | Argon2id 用 argon2-jvm `rawHash` 直接取 32 字节 | 需与第三方工具字节级互导 .vault（核对 `v=19`） |
| `VaultCrypto.kt` | 导出盐恒为文件内 `export_salt`，与登录 KEK 解耦 | 无 |
| `VaultBackup.kt` | 导出用文件内独立 `export_salt`，.vault 自包含 | 无 |
| `SqlBuilders.kt` | `limit/offset` 内联而非 `?` 绑定 | limit/offset 改为用户可控字符串时必须改回绑定 |
| `SqlBuilders.kt` | 搜索 LIKE 未转义 `%/_` | 需求要求把 `%/_` 当字面量时加 ESCAPE |
| `SqlBuilders.kt` | CATEGORY 排序按 `category_id` 而非分类名 | 需求要求按分类名字符串排序时 JOIN 后 ORDER BY |
| `VaultMerge.kt` | 分类合并"较新"退化为"采用导入 sort_order"（categories 无 updated_at） | 需在 categories 增加 updated_at 列 |
| `CategoryDao.kt` | "其他" 以 `name=="其他"` 识别为不可删种子 | 需按 id 识别时增加 is_seed 列 |
| `SqlBuilders.kt`/`CategoryDao.kt` | entryCount 统计 `is_deleted=0` | 若含已删计数则调整 |
| `Json.kt` | 手写 JSON 仅支持固定子集，未知类型退化为字符串 | 荷载演进为任意嵌套/未知字段时改引 kotlinx.serialization |
| `VaultDbHelper.kt`/`Schema.kt` | DDL 内联于 `Schema.kt` 而非读外部文件 | DB_VERSION 递增需按版本 ALTER 迁移 |
| `BiometricKeystore.kt` | 无 PIN 备援，主密码永久兜底 | 需求新增 PIN 备援时放松 UserAuthenticationRequired |
| `build_and_test.sh` | 纯源码复制到无中文临时目录编译（规避 JVM 中文路径） | JVM 工具链原生支持 UTF-8 中文路径后可去掉 |
| `RealVaultData.kt` | 单线程 Executor 串行所有 DAO 调用（不引协程库） | 需并行读降延迟时改 CachedThreadPool + 开 WAL |
| `RealVaultData.kt` | 主线程回调直用 `Handler(Looper.getMainLooper())`，无 Lifecycle 封装 | 需生命周期感知摘除时改 Lifecycle-aware 分发 |
| `RealVaultData.kt` | 导出文件名规则 `vault_export_<epochMillis>.vault`，写 `filesDir` | 需用户可选下载目录时改 MediaStore/SAF + 权限 |
| `RealVaultData.kt` | `exportVault` 仅返回文件名不返全路径 | 若 UI 需读回文件改返绝对路径/Content URI |
| `RealVaultData.kt` | 持 `writableDatabase` 不主动 close（进程级单例） | 需多进程/显式释放时改懒加载 + close |
| `RealVaultData.kt` | 后端 null 字段 → 前端 `""`（`password/website/notes/categoryName`） | 无（契约约定掩码态即空串） |
| `RealVaultData.kt` | 自检 `realVaultDataSelfTest` 不入 JVM 契约集（类依赖 Android 主线程） | 若拆分独立 JVM 映射模块则纳入契约自检 |
| `RealVaultData.kt` | `lock()`/`isLocked()` 同步委托 `UnlockManager`（与 Mock 对称），不套 runAsync、不暴露 getActiveDek 到 UI | 若 UI 需等锁完成回调则改异步 |

---

## 7. 待确认 / 已知边界

- **生物识别通道（`BiometricKeystore` / `UnlockManager.unlockWithBiometric`）依赖 Android Keystore Provider，无法在裸 JVM 验证**；其行为由 Android Instrumented 测试（需真机/模拟器 + BiometricPrompt）覆盖，不在此 `tests/contract` 范围。密码通道的 KEK 派生/DEK 解包/改主密码已在本 JVM 套件验证。
- **环境变量纪律不适用**：本层为离线 Android 组件，无服务端、无进程环境变量；密钥材料不落盘明文——DEK 仅驻内存（lock 显式清零），KEK 不持久化，生物识别密钥存 Android Keystore。与全局"密钥走环境变量"铁律的差异已在架构中记录（离线单用户模型）。
- 导出 `.vault` 为加密自包含文件；导入合并主键见契约 §3.4。
- **前端联调（RealVaultData）**：`RealVaultData` 仅依赖 Android（Context/Looper/Handler），不进入 `tests/contract` JVM 编译集，
  故 `bash build_and_test.sh` 仍输出 `ALL CONTRACT CHECKS OK`。其映射自检 `realVaultDataSelfTest()` 在 Android module 下运行
  （如 instrumented test 或 `MainApplication.onCreate` debug 分支调用），不在裸 JVM 验证。前端接入方式见 §4.1.1。
