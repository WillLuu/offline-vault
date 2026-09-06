# 离线密码本 · 本地数据 / 加密契约（Phase 3）

> 来源：arch-luchen ｜ 项目：纯离线 Android 原生密码管理器（Kotlin，min API 26）
> 范围：本地数据访问契约 + 加密规范 + 解锁/自动锁流程。**无远程 API**（项目无后端、无网络）。
> 纪律：零抽象默认（单实现不写接口/工厂/泛型仓储）；决策阶梯自上而下（臆测需求直接 YAGNI）。

---

## 1. 概述与范围

本契约是 Phase 4 研发的唯一事实源。它定义三件事：

1. **本地数据访问契约**：以伪接口（非 Kotlin 代码）描述各具体 DAO 的能力边界。
2. **加密规范**：密钥体系、KDF、AES-256-GCM 静态加密、`.vault` 文件格式、生物识别。
3. **解锁 / 自动锁流程**：首次初始化、启动解锁、切后台锁、超时锁、生物识别快捷解锁。

**架构师职责边界**：本文只产出契约与 DDL，不写 Kotlin/Android 实现代码。

---

## 2. 决策阶梯 & YAGNI 标注

按决策阶梯自上而下执行，凡臆测/僵尸需求直接 `YAGNI:` 砍掉。本项目已砍清单：

| 决策 | 结果 | 理由（决策阶梯） |
|---|---|---|
| 远程同步 / 云备份 | `YAGNI:` 砍 | 需求明确"完全离线、不依赖任何网络"；写入即违背核心约束 |
| 多设备实时同步 | `YAGNI:` 砍 | 同上；且引入账号/冲突合并，超出离线单库范围 |
| 账号系统 / 注册登录 | `YAGNI:` 砍 | 单用户本地保险库，主密码即唯一身份；无服务端 |
| 导出明文 CSV / 明文备份 | `YAGNI:` 砍 | 违反"强加密存储"；只产出加密 `.vault` |
| 预留字段（sync_status/cloud_id/device_id/etag/version_vector） | `YAGNI:` 砍 | 无同步即无用途，纯占位 |
| 通用 Repository 接口 / Factory / Strategy 抽象层 | `YAGNI:` 砍 | 每个能力仅一个实现，直接用具体 DAO（零抽象铁律） |
| PIN 码备用解锁 | `YAGNI:` 砍 | 需求仅列生物识别；不引入第二种本地凭证路径 |
| 条目硬删除（purge） | `YAGNI:` 砍 | 需求只要求增删改查 + 软删；硬删无业务诉求 |
| ContentProvider 向其他 App 共享数据 | `YAGNI:` 砍 | 安全优先，数据不对外暴露 |

**保留的"非显然但必要"设计**（非抽象，是真实加密需求）：
- **KEK/DEK 两层密钥**：主密码派生 KEK 仅用于"包裹"随机生成的 DEK；改主密码只需重包 DEK，**无需重加密全部条目**。这是密码管理器的真实必要结构，不是过度设计。
- **生物识别双通道**：DEK 同时被 Keystore 生物识别密钥包裹，提供快捷解锁，且改主密码不影响生物识别通道。

---

## 3. 本地数据访问契约（具体 DAO）

约定：所有方法为**具体类的方法**（非 interface）；数据访问前必须由 `UnlockManager` 解锁（DEK 在内存）。若调用时处于锁定态，`getActiveDek()` 返回 `null`，DAO 应拒绝并返回 `LOCKED` 错误。

### 3.1 PasswordEntryDao（密码条目）

```
class PasswordEntryDao {

  // 列表：搜索 + 排序 + 分类过滤 + 可选分页
  // search：对 name/username 做 LIKE（明文列，SQL 层完成，无需解密）
  // sortBy ∈ { NAME_ASC, NAME_DESC, CATEGORY_ASC, CATEGORY_DESC }
  // categoryId：null = 全部；includeDeleted 默认 false
  // 返回的每行 secret_blob 不解密（列表只展示掩码），password 字段置空
  fun listEntries(
        search: String? = null,
        sortBy: SortKey = NAME_ASC,
        categoryId: Long? = null,
        limit: Int = 100,
        offset: Int = 0
  ): List<PasswordEntryRow>

  // 详情：解密 secret_blob，回填 password/website/notes
  fun getEntry(id: Long): PasswordEntryRow?

  // 新增：加密 {password, website, notes} -> secret_blob
  fun createEntry(input: EntryInput): Long

  // 修改：重新加密 secret_blob，刷新 updated_at
  fun updateEntry(id: Long, input: EntryInput): Boolean

  // 软删：is_deleted=1，刷新 updated_at（不物理删除）
  fun deleteEntry(id: Long): Boolean
}
```

`EntryInput` = { name, username, password, website, notes, categoryId? }
`PasswordEntryRow` = { id, name, username, password(仅 getEntry 回填), website(仅 getEntry 回填), notes(仅 getEntry 回填), categoryId, categoryName, createdAt, updatedAt, isDeleted }

### 3.2 CategoryDao（分类，带条目计数）

```
class CategoryDao {

  // 列表：单条 JOIN+GROUP BY 取每条分类的条目计数（防 N+1）
  fun listCategories(): List<CategoryRow>   // CategoryRow 含 entryCount

  fun getCategory(id: Long): CategoryRow?

  fun createCategory(name: String, sortOrder: Int): Long

  fun updateCategory(id: Long, name: String? = null, sortOrder: Int? = null): Boolean

  // 非空分类禁止删除：存在未软删条目时返回 false（客户端需先改派条目）
  fun deleteCategory(id: Long): Boolean
}
```

> 规则：`其他` 为种子分类且不可删；用户分类仅当其下无有效条目时可删。删除时若需改派，客户端先 `updateEntry` 改 `categoryId` 再 `deleteCategory`。

### 3.3 AppSettingsStore（设置 + 密钥材料读取）

```
class AppSettingsStore {

  fun getSettings(): AppSettings                 // 不含任何密钥材料，仅用户设置
  fun updateSettings(patch: SettingsPatch): Boolean

  fun isInitialized(): Boolean                   // app_settings 行存在且 initialized=1

  // 由主密码派生 KEK 并校验 verifier；失败返回 null（密码错误）
  fun deriveKek(password: String): ByteArray?

  // 用 KEK 解包得到内存中的 DEK
  fun unwrapDek(kek: ByteArray): ByteArray

  // 改主密码：校验旧 KEK -> 解包 DEK -> 新盐新 KEK -> 重包 DEK + 新 verifier
  // DEK 不变，条目 secret_blob 无需重加密
  fun changeMasterPassword(oldPassword: String, newPassword: String): Boolean
}
```

`AppSettings` = { initialized, autoLockTimeoutSec, clipboardClearDelaySec, theme, biometricEnabled }
`SettingsPatch` = 上述字段的可空子集（局部更新）。

### 3.4 VaultBackup（导出 / 导入合并）

```
class VaultBackup {

  // 导出：useMasterPassword=true 用主密码经 Argon2id 派生 KEK（与登录同源，复用 deriveKek，文件头写登录 kdf_salt），用户只记一个主密码；
  //       false 用独立导出密码 + 文件内 export_salt 派生密钥；二者均加密全量数据 -> .vault 字节
  fun exportVault(password: String, useMasterPassword: Boolean): ByteArray

  // 导入：解密文件 -> 按合并主键合并进当前保险库 -> 返回合并报告
  fun importVault(file: ByteArray, password: String): MergeReport
}
```

`MergeReport` = { categoriesAdded, categoriesMerged, entriesAdded, entriesUpdated, entriesSkipped }

**合并主键规则**（importVault 内部）：
- 分类：按 `name` 匹配；命中则保留现有 id 并取较新的 `sort_order`；未命中则新建。
- 条目：合并主键 = `(name, username, category_name)` 三元组；命中则按 `updated_at` 取较新者覆盖；未命中则插入新记录（新 id）。
- `is_deleted=1` 的条目**不导入**（避免复活已删数据）。
- 导出荷载仅含 `categories` + `entries`（不含设备相关设置）；每条目携带解密后的 `{password, website, notes}` 后再用导出密钥整体重加密。

### 3.5 VaultCrypto（加密原语，具体类）

```
class VaultCrypto {
  fun deriveKey(password: String, salt: ByteArray, params: KdfParams): ByteArray  // 32 字节 AES-256 密钥
  fun encryptAesGcm(key: ByteArray, plaintext: ByteArray): AeadBlob               // nonce(12)+ciphertext+tag(16)
  fun decryptAesGcm(key: ByteArray, blob: AeadBlob): ByteArray                    // GCM 认证失败=密钥/数据错误
  fun wrapKey(kek: ByteArray, dek: ByteArray): AeadBlob
  fun unwrapKey(kek: ByteArray, blob: AeadBlob): ByteArray
  fun makeVerifier(kek: ByteArray): AeadBlob
  fun checkVerifier(kek: ByteArray, blob: AeadBlob): Boolean
}
```

`AeadBlob` = { nonce: 12 字节, ciphertext: 字节, tag: 16 字节 }（GCM 自带认证，无需额外 HMAC）。

### 3.6 UnlockManager（解锁 / 锁定会话，具体类）

```
class UnlockManager {
  fun initializeMasterPassword(password: String): Boolean   // 首次：建盐、派生 KEK、生成随机 DEK、包 DEK+verifier、可选包生物识别
  fun unlockWithPassword(password: String): Boolean         // 派生 KEK -> 校验 verifier -> 解包 DEK 驻留内存
  fun unlockWithBiometric(): Boolean                        // Keystore 解包 wrapped_dek_biometric -> DEK 驻留内存
  fun lock(): Unit                                          // 清除内存中的 DEK（切后台/超时触发）
  fun isLocked(): Boolean
  fun getActiveDek(): ByteArray?                            // null = 已锁定，数据访问被拒
}
```

---

## 4. 加密规范

### 4.1 密钥体系（KEK / DEK）

```
主密码 ──KDF(Argon2id)──► KEK (256-bit)
                              │
                              ├─ wrap ─► wrapped_dek        (存 app_settings)
                              └─ GCM  ─► verifier           (存 app_settings，解锁校验)

随机 DEK (256-bit) ──加密──► 每条 secret_blob
                    └─ wrap(Keystore生物识别密钥) ─► wrapped_dek_biometric (可选)
```

- **DEK**：初始化时 `CSPRNG` 生成 32 字节，是真正加密条目数据的密钥。
- **KEK**：由主密码经 KDF 派生，**不落盘**；仅用于包裹/解包 DEK 与生成 verifier。
- 改主密码 → 重派生 KEK → 重包同一 DEK；条目密文不变。

### 4.2 密钥派生 KDF（推荐 Argon2id，次选 SCrypt）

| 参数 | Argon2id（推荐） | SCrypt（次选） |
|---|---|---|
| 内存成本 | `65536` KB（64 MB） | N = 2^15 (32768) |
| 迭代/时间成本 | `t = 3` | r = 8 |
| 并行度 | `p = 1` | p = 1 |
| 盐 | 16 字节随机 | 16 字节随机 |
| 输出长度 | 32 字节 | 32 字节 |

> 选型理由（决策阶梯）：标准库/平台优先——Android 上可用 `androidx.security.crypto` 与 `argon2`/`scrypt` 原生或成熟库；避免自研。参数为移动端在安全性与解锁耗时（≈0.5–1s）间的折中，可在设置中固化，不开放给用户调节（YAGNI：不暴露 KDF 调参 UI）。

### 4.3 数据静态加密（AES-256-GCM）

- 算法：AES-256-GCM（AEAD，提供机密性 + 完整性 + 认证）。
- **Nonce/IV 管理**：每条 `secret_blob`、每个 `wrapped_*`、每次导出均使用**独立随机 12 字节 nonce**，随密文一并返回（`AeadBlob`）。**严禁 nonce 复用**（同密钥下复用即泄露）。
- 明文格式：`secret_blob` 明文 = JSON `{ "password": "...", "website": "...", "notes": "..." }`，整体加密。

### 4.4 `.vault` 导出文件格式

```
magic     : 4 字节  "VLT1"
version   : 1 字节  0x01
kdf_algo  : 1 字节  1=argon2id, 2=scrypt
kdf_params: 内存成本(4B) | 迭代(4B) | 并行度(1B) | 盐长(2B) | salt(变长)
nonce     : 12 字节  (导出密钥的 GCM nonce)
ciphertext: 变长     AES-256-GCM(导出密钥, 导出荷载)  // GCM tag(16B) 附于末尾
// 导出荷载(JSON) = { version, categories:[{name,sort_order}],
//                    entries:[{name,username,category_name,created_at,updated_at,
//                              secret:{password,website,notes}}] }
```

- 认证：依赖 GCM 自带 tag；**不额外加 HMAC**（零抽象：GCM 已足够，HMAC 属冗余）。
- 解密失败（GCM 认证不通过）= 密码错误或文件损坏 → 导入返回 `WRONG_PASSWORD`。
- 导出密钥来源：`useMasterPassword=true` 时用主密码经 Argon2id 派生 **KEK（与登录同源：复用 `deriveKek`，文件头写登录 `kdf_salt`+params，导入端据同一主密码重新派生，用户只记一个主密码）**；`useMasterPassword=false` 时用独立导出密码 + 文件内 `export_salt` 派生。两路导入均据文件头 `salt`+`params` 派生，文件自包含。

> 主密码哈希**仅用于验证解锁**（verifier 块经 GDF 认证），不可反解为明文，亦不用于直接加密条目（条目由 DEK 加密）。

### 4.5 Android Keystore 生物识别

- 初始化（若开启生物识别）：在 Keystore 生成 AES 密钥，设 `setUserAuthenticationRequired(true)` + 短时效生物识别绑定。
- 用该密钥包裹 DEK → `wrapped_dek_biometric`；`unlockWithBiometric()` 经系统生物识别弹窗确认后解包得到 DEK。
- DEK 与登录通道共用，故改主密码不影响生物识别通道；关闭生物识别即清空 `wrapped_dek_biometric` 与 Keystore 密钥。

---

## 5. 解锁 / 自动锁流程

```
[首次启动] --(无 app_settings 行)--> 初始化页：设主密码
        └─ initializeMasterPassword(): 建盐→KEK→随机DEK→wrapped_dek+verifier(+生物识别)
                                       写入 app_settings(id=1)，initialized=1

[常规启动] --(已 initialized)--> 解锁页
        ├─ 输主密码 --> unlockWithPassword(): 派生KEK→校验verifier→解包DEK驻留内存
        └─ 生物识别 --> unlockWithBiometric(): Keystore解包DEK驻留内存
        解锁成功 --> 进入列表

[运行中]
   ├─ onStop / 切后台  --> lock(): 立即清除内存 DEK（需求：切后台自动锁定）
   ├─ 前台空闲 ≥ auto_lock_timeout_sec --> lock()（需求：超时锁；0=不超时）
   └─ 任意数据访问前 getActiveDek()==null --> 拒绝并跳回解锁页

[锁定态] 所有 DAO 操作因 DEK 缺失而失败；重新走解锁流程恢复。
```

- **切后台 = 立即锁**（不等待超时），满足"应用切后台时自动锁定"。
- **超时锁**仅针对前台空闲场景。
- **内存安全**：`lock()` 须将 DEK 字节数组显式清零（fill 0）；不在日志/备份中落盘。

---

## 6. 搜索 / 排序方案（明文索引 vs 内存解密）

**推荐方案：name / username 存明文，SQL 层完成搜索与排序；secret_blob 始终加密，仅在 `getEntry`（详情）时解密。**

理由（决策阶梯 + 性能/安全权衡）：
1. **需求显式要求**"按名称或用户名搜索"且"按名称或分类排序"，需要可索引、可 `LIKE`、可 `ORDER BY` 的列——加密列无法在 SQL 层高效完成。
2. **避免每次输入都全量解密**：若在内存解密后过滤，每次按键需解密全部 `secret_blob` 并驻留明文，既耗 CPU 又延长明文在内存中的存活时间，反而增大泄露面。
3. **威胁模型匹配**：纯离线单用户本地库，无网络外泄路径；明文仅暴露 `name/username` 两项低敏感元数据（"你有哪些账号名"），换取即时搜索与排序体验，属可接受权衡。
4. **敏感字段不妥协**：`password / website / notes` 一律留在 `secret_blob` 加密，列表页只展示掩码，详情才解密。

> **备选（若威胁模型要求全加密）**：将 name/username 也并入 `secret_blob`，列表/搜索改为"解密全部 → 内存过滤"。代价是搜索变慢、明文驻留更久。默认不采用；如团队判定威胁模型需要，请在待确认项确认切换。

---

## 7. 防御设计

- **鉴权/授权**：本地单用户，"鉴权"= 解锁会话（DEK 在内存）。无多用户、无 per-record 所有权，故**无 IDOR 面**（IDOR 属多租户/远程场景，此处不适用，记录）。
- **分页**：`listEntries(limit, offset)` 默认 `limit=100`，防止大保险库一次性载入内存。
- **软删除**：`deleteEntry` 仅置 `is_deleted=1`，导出时跳过，可避免误删不可恢复（且无硬删，YAGNI 已砍）。
- **N+1 预防**：`CategoryDao.listCategories()` 用单条 `LEFT JOIN ... GROUP BY` 一次取回各分类条目计数，不为每个分类发一次计数查询。
- **密钥安全**：nonce 不复用；DEK 仅驻内存且锁定时清零；主密码不以明文存储，仅存 KDF 派生的 verifier。
- **剪贴板**：复制密码后按 `clipboard_clear_delay_sec` 延时清除（设置项；0=立即）。

---

## 8. ER 简述（对应 schema.sql）

```
categories(1) ──< (0..N) password_entries.category_id
app_settings(1) 单行，承载密钥材料与用户设置，不与条目外键关联
```

- `password_entries.category_id` → `categories.id`（`ON DELETE SET NULL`，分类被删且条目改派后归未分类）。
- `password_entries.secret_blob` 为加密载荷，与 `categories` 无加密耦合。
- 索引见 `schema.sql`：`idx_entries_name / username / cat_name / deleted`。

---

## 9. YAGNI 砍除记录（汇总）

```
YAGNI: 远程同步 / 云备份 - 需求明确完全离线，引入即违背核心约束
YAGNI: 多设备实时同步 - 无网络、无账号，且带来冲突合并复杂度
YAGNI: 账号系统 / 注册登录 - 单用户本地库，主密码即唯一身份
YAGNI: 导出明文 CSV - 违反强加密存储原则
YAGNI: 预留字段(sync_status/cloud_id/device_id/etag) - 无同步即无用途
YAGNI: 通用 Repository/Factory/Strategy 抽象层 - 每个能力仅单实现，零抽象铁律
YAGNI: PIN 码备用解锁 - 需求仅列生物识别
YAGNI: 条目硬删除(purge) - 需求只要求软删
YAGNI: ContentProvider 对外共享 - 安全优先，数据不暴露给其他 App
YAGNI: 导出文件额外 HMAC - GCM 已提供认证，冗余
YAGNI: KDF 参数用户可调 UI - 固定平台折中参数即可
```

---

## 10. 待确认项 → 已决策记录（冻结 v1 · 2026-07-27）

> 以下 5 项经人类在 Phase 3 冻结检查点（A/B 决策）确认。契约与 schema 维持原设计，无需改动。

1. **搜索/排序明文权衡** → 采纳方案 A：name/username 存明文索引，secret_blob 加密敏感字段。schema 不变。
2. **KEK/DEK 双层** → 已采纳。改主密码不重加密条目、生物识别独立通道。
3. **KDF 选型** → 采纳方案 B：保持 Argon2id（m=65536KB / t=3 / p=1），不降级到 SCrypt。
4. **机器可校验契约测试** → 同意在 Phase 4 由 be-muyuan 落实 `tests/contract/` 套件（可执行事实源）。
5. **生物识别范围** → 确认"主密码永远可用"为兜底，生物识别仅指纹/面容、无 PIN 备援。
```

> 2026-09-06 修订：KDF 提速为 m=32768KB / t=2 / p=1（原 65536/3/1）。旧库于下次主密码解锁成功后自动迁移（同盐重包 DEK/verifier，条目不重加密）。
