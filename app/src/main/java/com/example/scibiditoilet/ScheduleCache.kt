package com.example.scibiditoilet

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ScheduleCache {
    private var inMemoryDays: List<Day>? = null
    private var inMemoryBells: List<BellGroup>? = null
    var scheduleHtml: String? = null
    var bellsHtml: String? = null
    private val daysFile = "cache_days.json"
    private val bellsFile = "cache_bells.json"

    private const val PREFS_NAME = "meta"
    private const val KEY_LAST_UPDATE = "last_update"

    fun setDays(d: List<Day>) { inMemoryDays = d }
    fun setBells(b: List<BellGroup>) { inMemoryBells = b }

    fun saveMeta(ctx: Context, subs: String, week: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("subs", subs)
            .putString("week", week)
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply()
    }

    fun loadMeta(ctx: Context): Pair<String, String> {
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Pair(p.getString("subs", "") ?: "", p.getString("week", "") ?: "")
    }

    fun getLastUpdateTime(ctx: Context): Long {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_UPDATE, 0L)
    }

    suspend fun saveDays(ctx: Context, days: List<Day>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        days.forEach { d ->
            val obj = JSONObject().put("name", d.name)
            val larr = JSONArray()
            d.lessons.forEach { l ->
                larr.put(JSONObject()
                    .put("n", l.number).put("t", l.time).put("ti", l.title).put("m", l.meta)
                    .put("te", l.teacher).put("tid", l.teacherId).put("w", l.weekType))
            }
            obj.put("lessons", larr)
            arr.put(obj)
        }
        File(ctx.filesDir, daysFile).writeText(arr.toString())
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
    }

    suspend fun saveBells(ctx: Context, groups: List<BellGroup>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        groups.forEach { g ->
            val obj = JSONObject().put("title", g.title)
            val rarr = JSONArray()
            g.rows.forEach { r -> rarr.put(JSONObject().put("n", r.number).put("t", r.time).put("o", r.note)) }
            obj.put("rows", rarr)
            arr.put(obj)
        }
        File(ctx.filesDir, bellsFile).writeText(arr.toString())
    }

    suspend fun loadDays(ctx: Context): List<Day>? = withContext(Dispatchers.IO) {
        inMemoryDays?.let { return@withContext it }
        val f = File(ctx.filesDir, daysFile)
        if (!f.exists()) return@withContext null
        try {
            val arr = JSONArray(f.readText())
            val list = mutableListOf<Day>()
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                val larr = d.getJSONArray("lessons")
                val lessons = mutableListOf<Lesson>()
                for (j in 0 until larr.length()) {
                    val l = larr.getJSONObject(j)
                    lessons.add(Lesson(
                        l.optString("n", ""), l.optString("t", ""), l.optString("ti", ""),
                        l.optString("m", ""), l.optString("te", ""), l.optString("tid", ""), l.optString("w", "")
                    ))
                }
                list.add(Day(d.getString("name"), lessons))
            }
            inMemoryDays = list
            list
        } catch (_: Exception) { null }
    }

    suspend fun loadBells(ctx: Context): List<BellGroup>? = withContext(Dispatchers.IO) {
        inMemoryBells?.let { return@withContext it }
        val f = File(ctx.filesDir, bellsFile)
        if (!f.exists()) return@withContext null
        try {
            val arr = JSONArray(f.readText())
            val list = mutableListOf<BellGroup>()
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                val rarr = g.getJSONArray("rows")
                val rows = mutableListOf<BellRow>()
                for (j in 0 until rarr.length()) {
                    val r = rarr.getJSONObject(j)
                    rows.add(BellRow(r.getString("n"), r.getString("t"), r.getString("o")))
                }
                list.add(BellGroup(g.getString("title"), rows))
            }
            inMemoryBells = list
            list
        } catch (_: Exception) { null }
    }
}