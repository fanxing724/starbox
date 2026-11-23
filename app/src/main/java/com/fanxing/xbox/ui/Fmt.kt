package com.fanxing.xbox.ui

import java.util.Locale

object Fmt {
    fun size(n: Long): String {
        if (n <= 0) return "0B"
        val u = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f%s", v, u[i])
    }

    fun speed(n: Long): String = if (n <= 0) "—" else size(n) + "/s"
}
