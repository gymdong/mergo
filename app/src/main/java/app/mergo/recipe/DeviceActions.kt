package app.mergo.recipe

/** 레시피가 쓰는 기기 조작. 접근성 서비스가 구현하고, 테스트에서는 가짜로 바꾼다. */
interface DeviceActions {
    fun foregroundPkg(): String?
    fun launchApp(pkg: String): Boolean
    fun openUri(uri: String, pkg: String? = null): Boolean
    fun volume(): Int
    fun maxVolume(): Int
    fun setVolume(v: Int)
    fun isOnline(): Boolean
}
