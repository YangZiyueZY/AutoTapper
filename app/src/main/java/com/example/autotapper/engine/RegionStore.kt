package com.example.autotapper.engine

import android.content.Context
import com.example.autotapper.model.Region
import org.json.JSONArray

/**
 * 用户画的区域。插件通过 RegionApi 读写同一份数据，
 * 所以"识色用哪个区域"和"点击用哪个区域"指的是同一批区域。
 */
object RegionStore {

    private const val PREFS = "region_store_prefs"
    private const val KEY_REGIONS = "regions"

    fun load(context: Context): List<Region> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REGIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    Region.fromJson(obj)?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, regions: List<Region>) {
        val array = JSONArray()
        regions.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REGIONS, array.toString())
            .apply()
    }

    fun add(context: Context, region: Region): List<Region> {
        val updated = load(context) + region
        save(context, updated)
        return updated
    }

    fun removeAt(context: Context, index: Int): List<Region> {
        val current = load(context)
        val updated = if (index in current.indices) {
            current.toMutableList().apply { removeAt(index) }
        } else {
            current
        }
        save(context, updated)
        return updated
    }

    /** 区域编号标签：区域 01、区域 02 …（两位数字，和文档里的叫法一致）。 */
    fun labelFor(index: Int): String = "区域 %02d".format(index + 1)
}
