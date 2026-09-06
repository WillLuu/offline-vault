package com.qiqiao.passwordvault.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.qiqiao.passwordvault.R
import com.qiqiao.passwordvault.util.ThemePrefs

// 全部页面的统一基类（#SOFT UI 三主题机制）：
// - morandi 第三主题没有系统资源限定符，必须在 setContentView 之前 setTheme 运行时换肤；
// - light/dark/system 走 AppCompatDelegate（ThemePrefs.applyTheme 已在 Application 应用），此处不干预。
//
// 不做 onResume 自检 recreate：主题切换改为"清栈回到列表页"的正常跳转（新实例 onCreate 自然按新主题换肤）；
// recreate() 在这台设备上 isChangingConfigurations=false，会被误判切后台而触发自动锁。
open class PwdBaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        if (ThemePrefs.currentTheme(this) == "morandi") {
            setTheme(R.style.Theme_PasswordVault_Morandi)
        }
        super.onCreate(savedInstanceState)
        // P1 防截屏/最近任务缩略图：解锁页主密码、详情明文、生成器密码均不得被系统捕获。
        // 全 App 统一在此加，覆盖所有页面（审计 2026-09-06：全仓原先无任何 FLAG_SECURE）。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    // 全应用统一空闲锁（2026-09-06 用户需求：所有页面共用自动锁等待时间）：
    // 在事件分发的最上游拦截 ACTION_DOWN——即便被子控件（EditText/按钮）消费的触摸也能重置计时，
    // 修复原先只监听 decorView 导致"正在输入/点控件不重置、静置却不锁"的问题。
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.action == android.view.MotionEvent.ACTION_DOWN) {
            com.qiqiao.passwordvault.MainApplication.userInteracted()
        }
        return super.dispatchTouchEvent(ev)
    }
}