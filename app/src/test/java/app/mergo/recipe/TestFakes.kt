package app.mergo.recipe

import app.mergo.model.NodeSnapshot
import app.mergo.model.Rect4
import app.mergo.model.ScreenAgent
import app.mergo.model.ScreenReader
import app.mergo.model.VoiceIO
import app.mergo.model.YesNo

class FakeScreen : ScreenReader {
    var nodeList: List<NodeSnapshot> = emptyList()
    val clicks = mutableListOf<NodeSnapshot>()
    val taps = mutableListOf<Pair<Int, Int>>()
    var backs = 0
    var pkg: String? = null
    /** 클릭했을 때 화면이 어떻게 바뀌는지 테스트가 정한다. */
    var onClick: (NodeSnapshot) -> Unit = {}
    var onTap: () -> Unit = {}

    override fun nodes() = nodeList
    override fun click(nodeId: Int): Boolean {
        val n = nodeList.firstOrNull { it.id == nodeId } ?: return false
        clicks += n
        onClick(n)
        return true
    }
    override fun tap(x: Int, y: Int): Boolean { taps += x to y; onTap(); return true }
    override fun back(): Boolean { backs++; return true }
    override fun currentPackage() = pkg
}

class FakeDevice : DeviceActions {
    var fg: String? = "com.google.android.youtube"
    var vol = 0
    var max = 15
    var online = true
    val launched = mutableListOf<String>()
    val opened = mutableListOf<String>()
    var onOpenUri: (String) -> Unit = {}
    override fun foregroundPkg() = fg
    override fun launchApp(pkg: String): Boolean { launched += pkg; fg = pkg; return true }
    override fun openUri(uri: String, pkg: String?): Boolean { opened += uri; onOpenUri(uri); return true }
    override fun volume() = vol
    override fun maxVolume() = max
    override fun setVolume(v: Int) { vol = v }
    override fun isOnline() = online
}

class FakeAgent(private val result: Boolean = false, private val onRun: () -> Unit = {}) : ScreenAgent {
    val goals = mutableListOf<String>()
    override suspend fun achieve(goal: String): Boolean { goals += goal; onRun(); return result }
}

class FakeVoice(
    var yesNo: YesNo = YesNo.NONE,
    var heard: String? = null,
) : VoiceIO {
    val spoken = mutableListOf<String>()
    override suspend fun speak(text: String) { spoken += text }
    override suspend fun listen(timeoutMs: Long) = heard
    override suspend fun listenYesNo(timeoutMs: Long) = yesNo
}

private var ids = 1000
fun n(text: String? = null, desc: String? = null, viewId: String? = null, clickable: Boolean = true) =
    NodeSnapshot(ids++, text, desc, viewId, clickable, Rect4(0, 0, 1080, 600), visible = true)
