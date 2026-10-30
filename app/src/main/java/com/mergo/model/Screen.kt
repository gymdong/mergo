package com.mergo.model

// A가 구현(접근성), B의 ScreenAgent가 호출
interface ScreenReader {
    fun nodes(): List<NodeSnapshot>
    fun click(nodeId: Int): Boolean
    fun back(): Boolean
}
