# 离线密码本（Offline Vault）

纯离线 Android 原生密码管理器 —— 所有数据本地加密存储，无网络、无账号、无云同步。新拟态（Neumorphism）视觉，三主题可切换，阴影参数可按口味个性化调节。

> **状态**：v1.0.0（2026-09-06）—— 加密核心经独立代码审计核实，契约测试全绿。

## 特性

- **主密码解锁**：Argon2id 派生 KEK → verifier 校验 → AES-256-GCM 解包 DEK；支持生物识别两段式解锁（Android Keystore）
- **条目管理**：增删改查、软删除、列表掩码；搜索 / 5 种排序（名称升降序、分类升降序、最近更新）/ 分类过滤；列表全量加载无条数上限
- **自定义词条**：平台/帐号/密码固定词条之外，可增删改自定义词条（邮箱/网站/备注/任意标签），全局模板同步
- **批量录入**：粘贴自由文本笔记 → 智能解析为多条（中英文标签、`: ： =` 分隔、空行/重复标签切分）→ 预览可编辑可勾选 → 一键写入，分类按名称自动匹配或新建
- **分类管理**：种子分类、拖拽重排、按名称/条数/创建时间自动排序、「其他」不可删保护
- **密码生成器**：SecureRandom，可调长度（4–64）与字符集
- **数据管理页**：导出 / 导入 / 批量录入 / 分类管理 + 密码统计（总数、各分类条数、未分类）
- **加密备份**：导出 / 导入 `.vault`（主密码同源 KEK 或独立导出密码），SAF 用户自选保存位置；导入按 (名称, 账号, 分类) 合并、按更新时间取新
- **三主题**：浅色 / 深色 / 莫兰迪（+跟随系统）；新拟态阴影 16 参数可调（带实时预览与恢复默认），适配不同口味
- **安全策略**：切后台立即锁 / 前台空闲超时锁 / 复制后自动清除剪贴板（延时可配，切后台兜底冲刷）/ 全 App 禁止截屏（FLAG_SECURE）
- **细节体验**：方向感知左右滑动切页动画、可更换头像（EXIF 方向修正 + 采样解码）、全弹窗统一自绘新拟态风格（NeuDialog）
- **完全离线**：`AndroidManifest` 无网络权限，数据不离开设备

## 安全设计

- 存储加密：AES-256-GCM（每次独立随机 12B nonce，无复用；GCM 认证失败显式抛错，绝不静默返回错误明文）
- 密钥派生：Argon2id（m=32MB / t=2 / p=1，随机 16B 盐；高于 OWASP 移动端下限；旧参数库在下次密码解锁时自动静默迁移）
- 信封加密：随机 DEK 加密条目，KEK 仅由主密码派生、用毕即清零；主密码不以明文/可逆哈希存储，仅存加密 verifier
- 主密码 / 独立导出密码最小长度均为 8 位；内存 DEK 锁定时显式清零
- 生物识别：Android Keystore 两段式（per-op 认证 + setInvalidatedByBiometricEnrollment），指纹登记变更自动失效并自愈；主密码永远可用作兜底
- SQL 全参数化（ORDER BY 来自枚举、LIMIT/OFFSET 为受控整数）；应用无网络权限、`allowBackup=false`
- 数据/加密契约详见 [`api-contract.md`](./api-contract.md)

## 技术栈

- Android 原生 Kotlin（minSdk 26 / targetSdk 34），Material3 主题 + 自绘新拟态组件（NeuSurface / NeuSwitch / NeuDialog）
- SQLite（无 ORM，SQL 单源 `backend/src/vault/SqlBuilders.kt`）
- 纯 JVM 加密/存储层（`backend/src/vault/*`）与 UI 层分离，契约测试与 Android DAO 共用同一 DDL/SQL，两端零漂移
- KDF 采用 Bouncy Castle 纯 Java Argon2id（无 native 依赖，全 ABI 通用，与 argon2-jvm 测试向量逐字节对齐）

## 目录结构

```
backend/   加密、存储、解锁、备份（纯 JVM + Android SQLite 桥接），契约测试
frontend/  Android 应用（ui/ 页面、common/ 新拟态组件、data/ 桥接、util/ 工具）
api-contract.md   本地数据访问 + 加密规范契约
schema.sql        DB schema
```

## 构建

```bash
# 在 frontend/ 下（需 Android SDK，local.properties 指向 sdk.dir）
./gradlew assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

契约测试（纯 JVM，不依赖 Android SDK）：

```bash
# 在 backend/ 下；需 JDK 17 + Kotlin 2.0 编译器 + sqlite-jdbc + BouncyCastle
# 工具链路径可用环境变量覆盖：TOOLCHAIN_DIR / WORK_DIR
TOOLCHAIN_DIR=/path/to/toolchain bash build_and_test.sh   # 期望输出 ALL CONTRACT CHECKS OK
```

> 首次安装即进入「设置主密码」流程（两段输入，最小 8 位）。

## 开发者

- 品牌 / 标志：**17°**
- 作者：**拾柒**（Will）
- 邮箱：429234059@qq.com
- 许可：[MIT](./LICENSE)

## 已知边界（诚实清单）

- 条目 `name/username` 明文存储（便于搜索），`secret`（密码/网站/备注/词条）全程加密 —— 属设计权衡
- 进程被系统回收时无法保证剪贴板清除（系统级限制）；存活场景已用切后台兜底冲刷覆盖
- 解锁无失败次数限流，离线抗爆破依赖 Argon2id 派生成本与主密码强度（纯离线单机权衡）
- DB 升级迁移：`DB_VERSION=1`，schema 变更须在 `VaultDbHelper.onUpgrade` 写迁移段（版本化脚手架已就位）
- 仓库默认构建为 debug 签名，供学习与侧载；上架商店需替换 release 签名

## 鸣谢

- 加密算法参考 argon2-jvm / BouncyCastle；UI 语言参考 Soft UI / 新拟态设计实践
- 本项目为个人学习与实践作品，请勿用于生产级敏感凭证管理
