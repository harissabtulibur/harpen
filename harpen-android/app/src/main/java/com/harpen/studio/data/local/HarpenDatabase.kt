package com.harpen.studio.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String? = null,
    val colorArgb: Long = 0xFF0F766E,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val folderId: String? = null,
    val isNotebook: Boolean = false,
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    val hasPdf: Boolean = false,
    val hasImages: Boolean = false,
    val templateName: String = "DOTTED",
    val coverColorArgb: Long = 0xFF1E3A8A,
    val pageCount: Int = 1,
    val projectFilePath: String,
    val thumbnailPath: String? = null,
    val extractedText: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "color_palettes")
data class ColorPaletteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorsCsv: String,
    val isFavorite: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY name ASC")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :folderId")
    suspend fun deleteById(folderId: String)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE isTrashed = 0 ORDER BY updatedAt DESC")
    fun observeActiveNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE isTrashed = 1 ORDER BY updatedAt DESC")
    fun observeTrashedNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :noteId LIMIT 1")
    suspend fun getById(noteId: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Query("UPDATE notes SET isFavorite = :favorite, updatedAt = :updatedAt WHERE id = :noteId")
    suspend fun setFavorite(noteId: String, favorite: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET isTrashed = :trashed, updatedAt = :updatedAt WHERE id = :noteId")
    suspend fun setTrashed(noteId: String, trashed: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET title = :title, updatedAt = :updatedAt WHERE id = :noteId")
    suspend fun rename(noteId: String, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET folderId = :folderId, updatedAt = :updatedAt WHERE id = :noteId")
    suspend fun moveToFolder(noteId: String, folderId: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM notes WHERE id = :noteId")
    suspend fun deletePermanently(noteId: String)

    @Query("DELETE FROM notes WHERE isTrashed = 1")
    suspend fun emptyTrash()
}

@Dao
interface PaletteDao {
    @Query("SELECT * FROM color_palettes ORDER BY updatedAt DESC")
    fun observePalettes(): Flow<List<ColorPaletteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(palette: ColorPaletteEntity)

    @Query("DELETE FROM color_palettes WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Database(
    entities = [FolderEntity::class, NoteEntity::class, ColorPaletteEntity::class],
    version = 1,
    exportSchema = false
)
abstract class HarpenDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun noteDao(): NoteDao
    abstract fun paletteDao(): PaletteDao

    companion object {
        @Volatile
        private var instance: HarpenDatabase? = null

        fun getInstance(context: Context): HarpenDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HarpenDatabase::class.java,
                    "harpen_library.db"
                ).build().also { instance = it }
            }
        }
    }
}
