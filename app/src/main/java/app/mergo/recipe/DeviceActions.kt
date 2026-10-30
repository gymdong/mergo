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
    /** 와이파이 스위치가 켜져 있거나 켜지는 중인가. 패널의 스위치는 켜짐 여부를 노드로 알 수 없어, 끄는 일이 없게 먼저 확인한다. */
    fun isWifiEnabled(): Boolean
    /** 시스템 인터넷 패널(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)을 연다. */
    fun openInternetPanel(): Boolean
}
