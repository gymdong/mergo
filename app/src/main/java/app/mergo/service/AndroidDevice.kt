package app.mergo.service

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import app.mergo.recipe.DeviceActions

class AndroidDevice(
    private val context: Context,
    private val foreground: () -> String?,
) : DeviceActions {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val conn = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)

    override fun foregroundPkg(): String? = foreground()

    override fun launchApp(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    override fun openUri(uri: String, pkg: String?): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) intent.setPackage(pkg)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    override fun volume(): Int = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    override fun maxVolume(): Int = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    override fun setVolume(v: Int) = audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI)

    override fun isOnline(): Boolean {
        val caps = conn.getNetworkCapabilities(conn.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // 비행기 모드를 끈 직후에는 켜지는 중(ENABLING)이다. 이때도 켜진 것으로 봐야 스위치를 눌러 도로 끄지 않는다.
    override fun isWifiEnabled(): Boolean =
        wifi?.wifiState.let { it == WifiManager.WIFI_STATE_ENABLED || it == WifiManager.WIFI_STATE_ENABLING }

    override fun openInternetPanel(): Boolean {
        val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
