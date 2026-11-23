package com.fanxing.xbox.drive

/** 网盘注册表：按链接自动识别 */
object Registry {

    val all: List<Drive> = listOf(
        UcDrive(),
        QuarkDrive(),
        AliyunDrive(),
        BaiduDrive(),
        XunleiDrive(),
        Pan123Drive(),
        LanzouDrive(),
        GuangyaDrive(),
        Yidong139Drive(),
        Pan115Drive(),
        CtfileDrive(),
        PikPakDrive(),
        TianyiDrive(),
        WeiyunDrive()
    )

    fun byId(id: String): Drive? = all.firstOrNull { it.id == id }

    fun detect(url: String): Drive? = all.firstOrNull { it.match(url) }

    val names: String get() = all.joinToString("、") { it.name }
}
