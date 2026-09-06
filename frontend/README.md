# 离线密码本 · 前端 UI 层（frontend/）

纯离线 Android 原生密码管理器（Kotlin，min API 26，无后端、无网络）的 **UI 层** 实现。
事实源：`../api-contract.md` 与 `../schema.sql`（Phase 3 冻结）。

> Ardot 设计稿「离线密码本-移动端设计」(ID 708446533989122) 在本环境不可访问，故严格按
> api-contract.md §3 数据契约 + 主理人给定的 11 屏清单实现；视觉以契约「设计系统」章节为准。

---

## 1. 交付范围

- **11 个界面**（全部可交互，由 Mock 数据驱动）：
  1. 解锁页 `UnlockActivity`（主密码 / 生物识别入口 / 首次初始化入口）
  2. 密码列表页 `PasswordListActivity`（搜索 + 分类筛选 Tab + 卡片 + 新增 FAB + 设置入口，**浅/深两版由 DayNight 主题自动满足**）
  3. 条目详情页 `EntryDetailActivity`（掩码可切换 / 复制 / 编辑 / 删除）
  4. 新增/编辑条目页 `EntryEditActivity`（表单 + 唤起生成器）
  5. 密码生成器页 `PasswordGeneratorActivity`（长度滑块 + 字符集勾选 + 生成 + 复制）
  6. 分类管理页 `CategoryManageActivity`（列表含计数 / 新增 / 编辑 / 删除，「其他」不可删、非空不可删）
  7. 设置页 `SettingsActivity`（自动锁超时 / 剪贴板清除延时 / 主题 / 生物识别 / 导出 / 导入 / 分类管理 / 修改主密码）
  8. 导出备份页 `ExportBackupActivity`（主密码 / 独立导出密码 + 确认导出）
  9. 导出完成页 `ExportDoneActivity`（成功提示 + 文件位置）
  10. 导入备份页 `ImportBackupActivity`（选择 .vault + 密码 + 合并报告）
- **数据层边界**：`VaultData` 接口（契约 §3 全部方法）+ `MockVaultData`（内存实现，驱动 UI）。
- **纯函数 + 自检**：`util/`（掩码、生成器、搜索排序、主题解析）含 `assert` 静默自检，**不依赖任何测试框架**。
- **未实现**（backend/ 职责，按契约）：加密（AES-256-GCM / Argon2id / Keystore）、DAO/SQLite、真实 `.vault` 导出导入、真实生物识别解包。

---

## 2. 目录结构

```
frontend/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/qiqiao/passwordvault/
│       │   ├── MainApplication.kt          # 注入数据层 + 应用主题
│       │   ├── model/Models.kt             # 纯 Kotlin 数据类（唯一事实源映射）
│       │   ├── data/
│       │   │   ├── VaultData.kt            # UI→数据层 唯一接口边界
│       │   │   ├── Vault.kt                # 单例访问点（切换后端只改此处）
│       │   │   └── MockVaultData.kt        # 内存 Mock（驱动 UI 完整交互）
│       │   ├── util/                       # 纯函数 + assert 自检（无 Android 依赖）
│       │   │   ├── Mask.kt Generator.kt FilterSort.kt Theme.kt
│       │   │   ├── Time.kt ClipboardHelper.kt SelfChecks.kt
│       │   └── ui/<module>/                # 11 屏 Activity + 2 个 Adapter
│       └── res/                            # layout / values / values-night / drawable
```

---

## 3. 启动步骤

1. 用 **Android Studio**（或 `./gradlew`）打开 `frontend/` 目录。
2. 连接设备或启动模拟器（API 26+）。
3. 运行 `:app` 模块（已注入真实后端 `RealVaultData`——`backend/src/vault` 以同编译单元引入；`MockVaultData` 保留作 debug flavor 兜底）。
4. 首屏为解锁页：输入任意非空主密码即可解锁进入列表（Mock 不校验 verifier）。

> 环境无 Android SDK，本交付未在此环境实跑 Gradle 构建（Android 子集改动需真机 `./gradlew assembleDebug` 验证）；纯 Kotlin 子集（model/util）可用工具链 kotlinc 2.0.21 + `java -ea` 自检（见 §5）。

---

## 4. 依赖（原生优先）

**零新增第三方业务依赖**。仅使用 Android 平台标准 UI 工具包（AndroidX）：
`appcompat`、`material`(Material Components / Material 3)、`recyclerview`、`constraintlayout`、`biometric`。
无任何远程同步 / 网络 / 数据库第三方库（契合契约 YAGNI 清单）。

---

## 5. 自检运行方式（自检即文档）

`util/` 下纯函数为非平凡逻辑（边界/空值处理），同文件内 `assert` 静默自检，用 Kotlin JVM 直接运行：

```bash
cd frontend
# 纯 Kotlin 子集（model + util 非 Android 依赖部分；ClipboardHelper/ThemePrefs 依赖 android.* 不参与 JVM 自检）
kotlinc app/src/main/java/com/qiqiao/passwordvault/model/Models.kt \
       app/src/main/java/com/qiqiao/passwordvault/util/ChangeMaster.kt \
       app/src/main/java/com/qiqiao/passwordvault/util/FilterSort.kt \
       app/src/main/java/com/qiqiao/passwordvault/util/Generator.kt \
       app/src/main/java/com/qiqiao/passwordvault/util/Mask.kt \
       app/src/main/java/com/qiqiao/passwordvault/util/Theme.kt \
       app/src/main/java/com/qiqiao/passwordvault/util/Time.kt \
       app/src/main/java/com/qiqiao/passwordvault/util/SelfChecks.kt \
       -include-runtime -d selfcheck.jar
java -ea -jar selfcheck.jar   # -ea 使 assert 生效；全部通过打印 "SelfChecks OK" 且退出码 0，任一失败抛 AssertionError
```

覆盖：`testMask`（空/null 掩码）、`testGenerator`（空字符集 / 长度夹紧 4–64 / 字符集构成）、
`testFilterSort`（空/大小写/无命中搜索 + 四态排序）、`testTheme`（三态 + 未知值兜底）、
`testChangeMaster`（改主密码校验：旧/新空、新密码长度 ≥ 4（对齐后端 `MIN_NEW_PASSWORD_LEN=4`）、两次不一致、通过）。
（注意：Kotlin `assert` 需 `-ea` 才生效，本环境验证用 `java -ea -jar selfcheck.jar`；QA-019 的文档化缺口另单跟进。）

---

## 6. 如何接入 backend/ 数据层（牧远实现已就绪）

UI 只依赖 `VaultData` 接口，通过单例 `Vault.data` 调用。真实实现 `RealVaultData`（be-muyuan-2，落在 `../backend/src/vault/RealVaultData.kt`，**同包 `com.qiqiao.passwordvault.data`，直接 `implements VaultData`**）已按本接口 19 个方法实现（含 §5 新增 lock/isLocked，见下方「自动锁」小节），并已通过边界自检 `realVaultDataSelfTest()`。W6 新增的 `changeMasterPassword`（契约 §3.3）由 be-muyuan 在 `RealVaultData` 补桥接（后端 `AppSettingsStore.changeMasterPassword` 能力已具备），接口签名已冻结：`fun changeMasterPassword(oldPassword: String, newPassword: String, cb: Done<Unit>)`。

接入状态：**已接入**。`MainApplication.onCreate` 现直接注入 `RealVaultData(applicationContext)`（`backend/src/vault` 已作为 app module 源码集引入，argon2-jvm:2.7 + jna:5.14.0 依赖已在 app/build.gradle.kts 落地）：

```kotlin
// app/build.gradle.kts sourceSets.main.java.srcDirs += "${projectDir}/../../backend/src/vault"
// MainApplication.onCreate：
Vault.data = RealVaultData(applicationContext)
// 若需切回 Mock（debug 兜底）：Vault.data = MockVaultData()
```

真实实现要点（与契约对齐）：
- 每个方法在**后台单线程 Executor** 跑同步 DAO，结果/异常经 `Handler(Looper.getMainLooper())` 回主线程（不引协程库）。
- **类型映射**：后端 `password/website/notes/categoryName` 为 `String?` → 前端 `""`（其余字段一致）；纯映射函数抽成文件私有顶级函数 `mapEntryRow/mapCategoryRow/mapSettings/mapReport/toBackendEntryInput/toBackendSettingsPatch/toBackendSortKey`，便于自检。
- `exportVault` 写 `Context.getFilesDir()` 并返回文件名 `vault_export_<epochMillis>.vault`；`importVault` 两端都收 `ByteArray`，一致。
- 解锁经 `UnlockManager`（`getActiveDek` 经 lambda 注入 DAO，锁定态抛 `LockedException`）；`BiometricKeystore` 当前传 `null`（接口仅用前 4 个解锁方法）。
- 为避开与 `com.qiqiao.passwordvault.model` 同名类的 import 冲突，后端对 `vault.*` 模型用**显式别名导入**（如 `PasswordEntryRow as BackendEntryRow`），前端 `model` 作为接口规范返回类型——无需改动前端 model。

**密钥/密码永不在前端硬编码**（铁律）；Mock 中任何"假校验/假加密"处均已 `ponytail` 标注升级阈值，backend 接入后由真实实现取代。`MockVaultData` 可保留作 debug flavor 兜底。

> 完整集成步骤与 KDF 依赖见 `../backend/README.md`。

### 自动锁（契约 §5：切后台立即锁 + 前台空闲超时锁）

`VaultData` 已新增同步方法 `lock()` / `isLocked()`（对应 `UnlockManager`）。UI 在 `MainApplication` 用 `registerActivityLifecycleCallbacks` 维护**前台 Activity 计数** + 单 `Handler` 空闲计时（W5/#QA-014）：
- 计数归零（真正切后台）→ 调 `Vault.data.lock()`（立即锁，不等待超时）。
- 前台空闲 ≥ `auto_lock_timeout_sec`（设置值，0=不启用）→ `lock()` 并跳 `UnlockActivity`（清栈）；任意触屏 ACTION_DOWN 重置计时，页间跳转/回前台重读设置用最新值。
- 回前台（计数 1→1 跳变）且 `isLocked()` 为真、当前非解锁页 → 跳回 `UnlockActivity`（清栈）。

用计数而非单 Activity `onStop`，可避免页间跳转（如列表→详情）误锁。零新增依赖、不改各 Activity 父类。
旋转等配置变更（`isChangingConfigurations()`）**不触发锁、不取消空闲计时**（W9/#QA-022）：`onActivityStopped` 先正常递减计数保持平衡，再守卫跳过锁逻辑；新实例 `onStarted` 补位计数、`onResumed` 重挂计时。
`RealVaultData` 已实现 `lock()/isLocked()`（委托 `UnlockManager`）。Mock 已对读方法（listEntries/getEntry/listCategories/getSettings）在锁定态返回 `LOCKED` 失败，写方法由真实 backend 强制。

---

## 7. 四种状态覆盖（列表页）

密码列表页实现 `有数据 / 空 / 加载中 / 错误` 四态：
- 加载中：`pbLoading` 显示（Mock 200ms 延迟可见）。
- 空：`tvEmpty`（如搜索无结果）。
- 错误：`tvError`（点击重试）；**QA 触发方式**：在搜索框输入 `__error__` 即模拟列表加载失败。
- 有数据：`rvList`。

---

## 8. ponytail 留痕清单（23 处，均带触发阈值）

刻意简化处全部标注 `// ponytail: 理由 | 触发升级阈值`。按文件分布（计数口径 `grep -rnE '// ?ponytail:'`，frontend 全量含 build.gradle.kts；ExportBackupActivity 的行内非标准注释属 S2/#QA-006 延后，未计入）：
- `MockVaultData.kt`（9 处）：解锁不校验 verifier / getEntry 不解密 / 初始化不派生 KEK / 改密用内存 mock 密码比对（W6）/ 导出不真加密 / 导入返回示例报告 / lock 不清零 DEK 字节 / 列表 `__error__` 触发错误态（QA）/ 锁定态读方法返回 LOCKED——均在 backend 接入时移除或转真实强制。
- `MainApplication.kt`（4 处）：用 `ActivityLifecycleCallbacks` 前台计数实现自动锁（不引 lifecycle-process 库）/ 触屏重置不覆盖软键盘·硬件键盘·无障碍(TalkBack)·弹窗输入（W5）/ `getSettings` 失败保持上次超时值 / 旋转窗口（onStop→新 onStart）内空闲计时未取消、恰逢超时会误锁（W9）。
- `ThemePrefs.kt`（2 处）：共用私有 SharedPreferences，不引 androidx.preference / 运行时兜底而非抛异常。
- `Generator.kt`（1 处）：UI 已禁用生成按钮（生成随机源已用 `java.security.SecureRandom`，决策④）。
- `PasswordGeneratorActivity.kt`（1 处）：生成器复制走系统剪贴板直写、不经 ClipboardHelper（应用内中转，W2/#QA-002 延后）。
- `UnlockActivity.kt`（1 处）：生物识别走真实 `BiometricPrompt` 前先用 Toast 占位。
- `EntryEditActivity.kt` / `ImportBackupActivity.kt`（各 1 处）：使用 `startActivityForResult`（已废弃但 API26 稳定，未引 Activity Result API）。
- `PasswordListActivity.kt`（1 处）：250ms 手写防抖（未引入 Rx/debounce 库）。
- `build.gradle.kts`（2 处）：未接签名配置；跨目录 srcDir 指向 `backend/src/vault`（单一事实源，不复制）。

---

## 9. 待确认项（联调前需 be-muyuan / 主理人确认）

1. **生成器随机源**：已采用 `java.security.SecureRandom`（决策④）；前端 `Generator.kt` 直接使用，无需 backend 提供随机源接口。
2. **导出主密码路径**：UI 当前直接用输入密码作为导出密钥；真实应按契约 `§4.4` 用「主密码 + 文件内 `export_salt`」派生。backend 实现 `exportVault(useMasterPassword=true)` 时需注意独立 salt。
3. **主题即时应用**：设置页主题切换写入 `SharedPreferences` 并即时 `AppCompatDelegate` 生效，未等异步 `getSettings`；backend 的 `AppSettings.theme` 应以该值为准，避免回写覆盖。
4. **剪贴板 delay=0 语义**：已统一为「0=**永不自动清除**」（决策① + W8/#QA-017 已闭环）。`ClipboardHelper` `clearDelaySec<=0` 不启动清除定时器；设置页文案 `settings_clipboard` 已同步为「剪贴板清除延时（秒，0=永不自动清除）」。schema.sql 注释由 be-muyuan 同步（前端不动）。
5. **生物识别范围**：按契约「主密码永远可用」兜底，前端生物识别入口仅在 `biometricEnabled` 时展示；真实 `BiometricPrompt` 解包 DEK 由 backend 接入。
