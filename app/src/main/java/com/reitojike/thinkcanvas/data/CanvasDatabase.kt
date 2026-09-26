package com.reitojike.thinkcanvas.data

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Transaction
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.reitojike.thinkcanvas.canvas.TextColor
import com.reitojike.thinkcanvas.canvas.TextElement
import com.reitojike.thinkcanvas.canvas.TextKind

@Entity(tableName = "boards")
data class BoardRow(
    @PrimaryKey val id: Long = 1,
    val name: String = "無題のボード",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "text_elements")
data class TextElementRow(
    @PrimaryKey val id: String,
    val boardId: Long = 1,
    val text: String,
    val kind: String,
    val color: String,
    val x: Float,
    val y: Float,
) {
    fun toModel() = TextElement(id, text, TextKind.valueOf(kind), TextColor.valueOf(color), x, y)

    companion object {
        fun fromModel(element: TextElement) = TextElementRow(
            id = element.id,
            text = element.text,
            kind = element.kind.name,
            color = element.color.name,
            x = element.x,
            y = element.y,
        )
    }
}

@Dao
interface CanvasDao {
    @Query("SELECT * FROM boards WHERE id = 1")
    suspend fun firstBoard(): BoardRow?

    @Query("SELECT * FROM text_elements WHERE boardId = 1 ORDER BY rowid")
    suspend fun elements(): List<TextElementRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putBoard(board: BoardRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putElements(elements: List<TextElementRow>)

    @Query("DELETE FROM text_elements WHERE boardId = 1")
    suspend fun clearElements()

    @Transaction
    suspend fun replaceAll(elements: List<TextElementRow>) {
        clearElements()
        putElements(elements)
        putBoard(BoardRow())
    }
}

@Database(entities = [BoardRow::class, TextElementRow::class], version = 1, exportSchema = true)
abstract class CanvasDatabase : RoomDatabase() {
    abstract fun canvasDao(): CanvasDao

    companion object {
        fun open(context: Context): CanvasDatabase = Room.databaseBuilder(
            context.applicationContext,
            CanvasDatabase::class.java,
            "think-canvas.db",
        ).setDriver(AndroidSQLiteDriver()).build()
    }
}
