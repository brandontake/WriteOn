package com.ezpnix.writeon.domain.model
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.ezpnix.writeon.core.database.Converters

/**
 * The three columns the list screens filter and sort on, indexed as of database v10.
 *
 * Every save or pin used to trigger a full-table scan (and, through the decrypting DAO, a full
 * pass of Keystore work); with an index the same query reads only the rows it needs.
 */
@Entity(
    tableName = "notes-table",
    indices = [Index("trashed"), Index("pinned"), Index("updated_at")]
)
@TypeConverters(Converters::class)
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "note-name")
    val name: String,
    @ColumnInfo(name = "note-description")
    val description: String,
    @ColumnInfo(name = "pinned")
    val pinned: Boolean = false,
    @ColumnInfo(name = "encrypted")
    val encrypted: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    var trashed: Boolean = false,
    @ColumnInfo(name = "tags")
    val tags: List<String> = emptyList()
)