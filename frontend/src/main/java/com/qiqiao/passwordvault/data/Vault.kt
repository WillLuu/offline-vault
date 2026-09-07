package com.qiqiao.passwordvault.data

// 数据层访问点（单例）。UI 统一通过 Vault.data 调用。
// 切换真实后端时只需在此处把 MockVaultData() 换成 backend 的实现，UI 零改动。
// 单实例、无工厂（零抽象铁律）。
object Vault {
    // 由 MainApplication.onCreate 注入；默认 Mock
    lateinit var data: VaultData
}
