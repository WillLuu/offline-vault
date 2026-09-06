package com.qiqiao.passwordvault.util

import android.app.Activity
import android.content.Intent
import com.qiqiao.passwordvault.R

/**
 * 底部导航切页动画（2026-09-06 用户需求：方向感知左右滑动）。
 *
 * 目的地按导航栏顺序编号：主页0 / 数据1 / 新增2 / 设置3。
 *  - 往序号更大的页面切（如 主页→数据）＝前进：整体左移（新页从右进，旧页左移压暗）；
 *  - 往序号更小的页面切（如 数据→主页）＝后退：整体右移（新页从左回位，旧页右滑出）；
 *  - 同序号兜底：沿用淡入+轻移（nav_enter/nav_exit）。
 *
 * 单一事实源：四个带底部导航的页面（列表/数据/编辑/设置）统一走 [go]，避免各页动画漂移。
 */
object NavAnim {
    const val DEST_HOME = 0
    const val DEST_DATA = 1
    const val DEST_ADD = 2
    const val DEST_SETTINGS = 3

    fun go(activity: Activity, intent: Intent, from: Int, to: Int) {
        activity.startActivity(intent)
        when {
            to > from -> activity.overridePendingTransition(
                R.anim.nav_forward_enter, R.anim.nav_forward_exit
            )
            to < from -> activity.overridePendingTransition(
                R.anim.nav_backward_enter, R.anim.nav_backward_exit
            )
            else -> activity.overridePendingTransition(R.anim.nav_enter, R.anim.nav_exit)
        }
    }
}
