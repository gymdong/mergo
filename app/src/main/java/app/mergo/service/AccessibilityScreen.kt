package app.mergo.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import app.mergo.model.NodeSnapshot
import app.mergo.model.Rect4
import app.mergo.model.ScreenReader

/** 접근성 노드 트리를 [NodeSnapshot]으로 줄이고, 번호로 클릭·탭·뒤로 가기를 실행한다. */
class AccessibilityScreen(private val service: AccessibilityService) : ScreenReader {
    private var lastNodes: Map<Int, AccessibilityNodeInfo> = emptyMap()

    @Synchronized
    override fun nodes(): List<NodeSnapshot> {
        val root = service.rootInActiveWindow ?: return emptyList<NodeSnapshot>().also { lastNodes = emptyMap() }
        val out = mutableListOf<NodeSnapshot>()
        val refs = HashMap<Int, AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val r = Rect()
        while (queue.isNotEmpty() && refs.size < MAX_NODES) {
            val n = queue.removeFirst()
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
            if (!n.isVisibleToUser) continue
            n.getBoundsInScreen(r)
            val id = refs.size
            refs[id] = n
            out += NodeSnapshot(
                id = id,
                text = n.text?.toString()?.takeIf { it.isNotBlank() },
                desc = n.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                viewId = n.viewIdResourceName,
                clickable = n.isClickable,
                bounds = Rect4(r.left, r.top, r.right, r.bottom),
                visible = true,
            )
        }
        lastNodes = refs
        return out
    }

    @Synchronized
    override fun click(nodeId: Int): Boolean {
        val n = lastNodes[nodeId] ?: return false
        // 글자 노드 자체는 클릭을 못 받는 경우가 많아, 클릭 가능한 조상을 찾아 누른다.
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            depth++
        }
        val r = Rect().also(n::getBoundsInScreen)
        return tap(r.centerX(), r.centerY())
    }

    override fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    override fun back(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    override fun currentPackage(): String? = service.rootInActiveWindow?.packageName?.toString()

    /** 사전 검증용 노드 덤프. 한 줄에 노드 하나. */
    fun dumpText(): String = nodes().joinToString("\n") { n ->
        "#${n.id} text=${n.text} desc=${n.desc} id=${n.viewId} click=${n.clickable} ${n.bounds}"
    }

    private companion object {
        const val MAX_NODES = 400
    }
}
