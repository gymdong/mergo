package com.mergo.report

import android.content.Context
import com.mergo.model.FamilyLog
import com.mergo.model.FamilyLogEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** 실제 전송은 하지 않고 기기 안에만 남긴다. */
class PrefsFamilyLog(context: Context) : FamilyLog {
    private val prefs = context.applicationContext.getSharedPreferences("mergo_family_log", Context.MODE_PRIVATE)

    override fun add(entry: FamilyLogEntry) {
        val kept = (all() + entry).takeLast(MAX_ENTRIES)
        prefs.edit().putString(KEY, FamilyLogCodec.encode(kept)).apply()
    }

    override fun all(): List<FamilyLogEntry> = FamilyLogCodec.decode(prefs.getString(KEY, null))

    private companion object {
        const val KEY = "entries"
        const val MAX_ENTRIES = 100
    }
}

object FamilyLogCodec {
    fun encode(entries: List<FamilyLogEntry>): String = buildJsonArray {
        entries.forEach {
            addJsonObject {
                put("ts", it.ts)
                put("title", it.title)
                put("outcome", it.outcome)
            }
        }
    }.toString()

    fun decode(raw: String?): List<FamilyLogEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = try {
            Json.parseToJsonElement(raw) as? JsonArray
        } catch (e: IllegalArgumentException) {
            null
        }
        return array.orEmpty().filterIsInstance<JsonObject>().mapNotNull { item ->
            val ts = item["ts"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val title = item["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            FamilyLogEntry(ts, title, item["outcome"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }
}
