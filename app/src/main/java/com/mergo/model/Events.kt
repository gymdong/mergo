package com.mergo.model

// A가 채우고 B가 읽음
data class ScreenEvent(
    val ts: Long,
    val pkg: String,
    val title: String?,
    val texts: List<String>,
    val userAction: Boolean,
    val kind: EventKind,
)

enum class EventKind { WINDOW_CHANGED, CONTENT_CHANGED, CLICK, SCROLL, KEY, BACK }

data class NodeSnapshot(
    val id: Int,
    val text: String?,
    val desc: String?,
    val clickable: Boolean,
    val bounds: Rect4,
    val visible: Boolean,
)

data class Rect4(val l: Int, val t: Int, val r: Int, val b: Int)

data class Baseline(
    val pkg: String,
    val mediaTitle: String?,
    val volume: Int,
    val online: Boolean,
    val updatedAt: Long,
)
