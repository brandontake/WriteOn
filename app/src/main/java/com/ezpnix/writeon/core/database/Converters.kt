package com.ezpnix.writeon.core.database

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Room type converter for the note's tag list.
 *
 * It lives in `core` rather than `data` because the `Note` entity (in `domain`) carries the
 * `@TypeConverters` annotation, and domain must not depend on data.
 *
 * The column is stored as a JSON array, which is why tag filtering happens in memory rather than
 * with SQL: `WHERE :tag IN (tags)` would compare a scalar against one text column.
 */
class Converters {
    private val gson = Gson()

    @androidx.room.TypeConverter
    fun fromStringList(value: List<String>): String {
        return gson.toJson(value)
    }

    @androidx.room.TypeConverter
    fun toStringList(value: String): List<String> {
        val listType = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, listType) ?: emptyList()
    }
}
