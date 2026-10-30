package com.mergo.model

// A의 실패 처리가 add로 기록하고, B의 가족 리포트가 all로 읽음
data class FamilyLogEntry(val ts: Long, val title: String, val outcome: String)

interface FamilyLog {
    fun add(entry: FamilyLogEntry)
    fun all(): List<FamilyLogEntry>
}
