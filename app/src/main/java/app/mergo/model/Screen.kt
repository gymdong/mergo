package app.mergo.model

/** 현재 화면을 읽고 조작하는 창구. 기기 쪽(접근성 서비스)이 구현한다. */
interface ScreenReader {
    /** 보이는 노드를 번호를 붙여 돌려준다. 번호는 다음 nodes() 호출 전까지 유효하다. */
    fun nodes(): List<NodeSnapshot>
    /** 노드를 클릭한다. 클릭을 못 받는 노드면 위치 중심에 탭 제스처를 보낸다. */
    fun click(nodeId: Int): Boolean
    fun tap(x: Int, y: Int): Boolean
    fun back(): Boolean
    fun currentPackage(): String?
}

interface ScreenAgent {
    suspend fun achieve(goal: String): Boolean
}
