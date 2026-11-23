package com.fanxing.xbox.ui

import android.app.Activity
import com.fanxing.xbox.R
import com.fanxing.xbox.store.Creds

/**
 * 自定义配色。固定浅色底，只换强调色 —— 不做明暗切换。
 * 用主题覆盖层实现，避免逐控件手动着色漏掉地方。
 */
object Palette {

    val names = arrayOf("靛蓝", "青碧", "橙柚", "樱粉", "墨紫", "森绿", "海蓝", "石墨")

    private val styles = intArrayOf(
        R.style.Theme_Xbox_Color_Indigo,
        R.style.Theme_Xbox_Color_Teal,
        R.style.Theme_Xbox_Color_Orange,
        R.style.Theme_Xbox_Color_Pink,
        R.style.Theme_Xbox_Color_Violet,
        R.style.Theme_Xbox_Color_Green,
        R.style.Theme_Xbox_Color_Blue,
        R.style.Theme_Xbox_Color_Graphite
    )

    val swatches = intArrayOf(
        R.color.c_indigo, R.color.c_teal, R.color.c_orange, R.color.c_pink,
        R.color.c_violet, R.color.c_green, R.color.c_blue, R.color.c_graphite
    )

    fun nameOf(i: Int) = names.getOrElse(i) { names[0] }

    /** 在 setContentView 之前调用 */
    fun apply(activity: Activity) {
        val i = Creds.get(activity).colorTheme.coerceIn(0, styles.size - 1)
        activity.setTheme(styles[i])
    }
}
