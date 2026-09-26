package com.thinkcanvas.data

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
import com.thinkcanvas.canvas.TextColor
import com.thinkcanvas.canvas.TextElement
import com.thinkcanvas.canvas.TextKind
import com.thinkcanvas.canvas.ArrowElement
import com.thinkcanvas.canvas.ArrowEnd
import com.thinkcanvas.canvas.ShapeElement
import com.thinkcanvas.canvas.ShapeKind

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

@Entity(tableName = "spatial_elements")
data class SpatialElementRow(
    @PrimaryKey val id: String,
    val boardId: Long = 1,
    val kind: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val color: String,
    val name: String,
) {
    fun toModel() = ShapeElement(id, ShapeKind.valueOf(kind), x, y, width, height, TextColor.valueOf(color), name)

    companion object {
        fun fromModel(element: ShapeElement) = SpatialElementRow(
            id = element.id, kind = element.kind.name, x = element.x, y = element.y,
            width = element.width, height = element.height, color = element.color.name, name = element.name,
        )
    }
}

@Entity(tableName = "arrow_elements")
data class ArrowElementRow(
    @PrimaryKey val id: String,
    val boardId: Long = 1,
    val fromTargetId: String?,
    val fromU: Float?,
    val fromV: Float?,
    val fromX: Float?,
    val fromY: Float?,
    val toTargetId: String?,
    val toU: Float?,
    val toV: Float?,
    val toX: Float?,
    val toY: Float?,
    val bend: Float,
) {
    private fun end(targetId: String?, u: Float?, v: Float?, x: Float?, y: Float?): ArrowEnd =
        if (targetId != null) ArrowEnd.Attached(targetId, requireNotNull(u), requireNotNull(v))
        else ArrowEnd.Free(requireNotNull(x), requireNotNull(y))

    fun toModel() = ArrowElement(
        id = id,
        from = end(fromTargetId, fromU, fromV, fromX, fromY),
        to = end(toTargetId, toU, toV, toX, toY),
        bend = bend,
    )

    companion object {
        fun fromModel(arrow: ArrowElement): ArrowElementRow {
            val from = arrow.from
            val to = arrow.to
            return ArrowElementRow(
                id = arrow.id,
                fromTargetId = (from as? ArrowEnd.Attached)?.targetId,
                fromU = (from as? ArrowEnd.Attached)?.u,
                fromV = (from as? ArrowEnd.Attached)?.v,
                fromX = (from as? ArrowEnd.Free)?.x,
                fromY = (from as? ArrowEnd.Free)?.y,
                toTargetId = (to as? ArrowEnd.Attached)?.targetId,
                toU = (to as? ArrowEnd.Attached)?.u,
                toV = (to as? ArrowEnd.Attached)?.v,
                toX = (to as? ArrowEnd.Free)?.x,
                toY = (to as? ArrowEnd.Free)?.y,
                bend = arrow.bend,
            )
        }
    }
}

@Dao
interface CanvasDao {
    @Query("SELECT * FROM boards WHERE id = 1")
    suspend fun firstBoard(): BoardRow?

    @Query("SELECT * FROM text_elements WHERE boardId = 1 ORDER BY rowid")
    suspend fun elements(): List<TextElementRow>

    @Query("SELECT * FROM spatial_elements WHERE boardId = 1 ORDER BY rowid")
    suspend fun spatialElements(): List<SpatialElementRow>

    @Query("SELECT * FROM arrow_elements WHERE boardId = 1 ORDER BY rowid")
    suspend fun arrows(): List<ArrowElementRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putBoard(board: BoardRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putElements(elements: List<TextElementRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSpatialElements(elements: List<SpatialElementRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putArrows(arrows: List<ArrowElementRow>)

    @Query("DELETE FROM text_elements WHERE boardId = 1")
    suspend fun clearElements()

    @Query("DELETE FROM spatial_elements WHERE boardId = 1")
    suspend fun clearSpatialElements()

    @Query("DELETE FROM arrow_elements WHERE boardId = 1")
    suspend fun clearArrows()

    @Query("UPDATE boards SET updatedAt = :updatedAt WHERE id = 1")
    suspend fun touchBoard(updatedAt: Long)

    @Transaction
    suspend fun replaceAll(elements: List<TextElementRow>) {
        replaceAll(elements, emptyList(), emptyList())
    }

    @Transaction
    suspend fun replaceAll(
        elements: List<TextElementRow>,
        spatialElements: List<SpatialElementRow>,
        arrows: List<ArrowElementRow>,
    ) {
        if (firstBoard() == null) putBoard(BoardRow())
        clearElements()
        clearSpatialElements()
        clearArrows()
        putElements(elements)
        putSpatialElements(spatialElements)
        putArrows(arrows)
        touchBoard(System.currentTimeMillis())
    }
}

@Database(
    entities = [BoardRow::class, TextElementRow::class, SpatialElementRow::class, ArrowElementRow::class],
    version = 1,
    exportSchema = true,
)
abstract class CanvasDatabase : RoomDatabase() {
    abstract fun canvasDao(): CanvasDao

    companion object {
        fun open(context: Context): CanvasDatabase = Room.databaseBuilder(
            context.applicationContext,
            CanvasDatabase::class.java,
            "thinkcanvas.db",
        ).setDriver(AndroidSQLiteDriver()).build()
    }
}
