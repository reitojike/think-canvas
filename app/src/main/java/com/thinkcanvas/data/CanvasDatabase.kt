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
import androidx.room3.migration.Migration
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import com.thinkcanvas.canvas.TextColor
import com.thinkcanvas.canvas.TextElement
import com.thinkcanvas.canvas.TextKind
import com.thinkcanvas.canvas.ArrowElement
import com.thinkcanvas.canvas.ArrowEnd
import com.thinkcanvas.canvas.ShapeElement
import com.thinkcanvas.canvas.ShapeKind
import com.thinkcanvas.canvas.BoardSnapshot

@Entity(tableName = "boards")
data class BoardRow(
    @PrimaryKey val id: Long = 1,
    val name: String = "無題のボード",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "text_elements")
data class TextElementRow(
    @PrimaryKey val id: String,
    val boardId: Long,
    val text: String,
    val kind: String,
    val color: String,
    val x: Float,
    val y: Float,
) {
    fun toModel() = TextElement(id, text, TextKind.valueOf(kind), TextColor.valueOf(color), x, y)

    companion object {
        fun fromModel(boardId: Long, element: TextElement) = TextElementRow(
            id = element.id,
            boardId = boardId,
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
    val boardId: Long,
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
        fun fromModel(boardId: Long, element: ShapeElement) = SpatialElementRow(
            id = element.id, boardId = boardId, kind = element.kind.name, x = element.x, y = element.y,
            width = element.width, height = element.height, color = element.color.name, name = element.name,
        )
    }
}

@Entity(tableName = "arrow_elements")
data class ArrowElementRow(
    @PrimaryKey val id: String,
    val boardId: Long,
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
        fun fromModel(boardId: Long, arrow: ArrowElement): ArrowElementRow {
            val from = arrow.from
            val to = arrow.to
            return ArrowElementRow(
                id = arrow.id,
                boardId = boardId,
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
    @Query("SELECT * FROM boards ORDER BY updatedAt DESC, id DESC")
    suspend fun boards(): List<BoardRow>

    @Query("SELECT * FROM boards WHERE id = :boardId")
    suspend fun board(boardId: Long): BoardRow?

    @Query("SELECT * FROM text_elements WHERE boardId = :boardId ORDER BY rowid")
    suspend fun elements(boardId: Long): List<TextElementRow>

    @Query("SELECT * FROM spatial_elements WHERE boardId = :boardId ORDER BY rowid")
    suspend fun spatialElements(boardId: Long): List<SpatialElementRow>

    @Query("SELECT * FROM arrow_elements WHERE boardId = :boardId ORDER BY rowid")
    suspend fun arrows(boardId: Long): List<ArrowElementRow>

    @Query("SELECT * FROM ink_strokes WHERE boardId = :boardId ORDER BY rowid")
    suspend fun inkStrokes(boardId: Long): List<InkStrokeRow>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun putBoard(board: BoardRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putElements(elements: List<TextElementRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSpatialElements(elements: List<SpatialElementRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putArrows(arrows: List<ArrowElementRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putInkStrokes(strokes: List<InkStrokeRow>)

    @Query("DELETE FROM text_elements WHERE boardId = :boardId")
    suspend fun clearElements(boardId: Long)

    @Query("DELETE FROM spatial_elements WHERE boardId = :boardId")
    suspend fun clearSpatialElements(boardId: Long)

    @Query("DELETE FROM arrow_elements WHERE boardId = :boardId")
    suspend fun clearArrows(boardId: Long)

    @Query("DELETE FROM ink_strokes WHERE boardId = :boardId")
    suspend fun clearInkStrokes(boardId: Long)

    @Query("SELECT MAX(updatedAt) FROM boards")
    suspend fun latestUpdateTime(): Long?

    @Query("UPDATE boards SET updatedAt = :updatedAt WHERE id = :boardId")
    suspend fun setUpdatedAt(boardId: Long, updatedAt: Long): Int

    @Query("UPDATE boards SET name = :name, updatedAt = :updatedAt WHERE id = :boardId")
    suspend fun setName(boardId: Long, name: String, updatedAt: Long): Int

    @Query("DELETE FROM boards WHERE id = :boardId")
    suspend fun removeBoard(boardId: Long): Int

    suspend fun nextUpdateTime(requested: Long = System.currentTimeMillis()): Long =
        maxOf(requested, (latestUpdateTime() ?: 0L) + 1L)

    @Transaction
    suspend fun renameBoard(boardId: Long, name: String, updatedAt: Long): Int =
        if (board(boardId) == null) 0 else setName(boardId, name, nextUpdateTime(updatedAt))

    @Transaction
    suspend fun createBoard(name: String = "無題のボード", updatedAt: Long = System.currentTimeMillis()): BoardRow {
        // 最大 ID を再利用すると、削除前の遅延保存が新しいボードへ書き込める。
        var id: Long
        do {
            id = java.util.concurrent.ThreadLocalRandom.current().nextLong(2, Long.MAX_VALUE)
        } while (board(id) != null)
        val row = BoardRow(id, name, nextUpdateTime(updatedAt))
        putBoard(row)
        return row
    }

    @Transaction
    suspend fun createBoardWithSnapshot(name: String, snapshot: BoardSnapshot): BoardRow {
        val row = createBoard(name)
        replaceAll(row.id, snapshot.texts.map { TextElementRow.fromModel(row.id, it) },
            snapshot.shapes.map { SpatialElementRow.fromModel(row.id, it) },
            snapshot.arrows.map { ArrowElementRow.fromModel(row.id, it) },
            snapshot.ink.flatMap { InkStrokeRow.fromModel(row.id, it) })
        return row
    }

    @Transaction
    suspend fun replaceAll(
        boardId: Long,
        elements: List<TextElementRow>,
        spatialElements: List<SpatialElementRow>,
        arrows: List<ArrowElementRow>,
        inkStrokes: List<InkStrokeRow> = emptyList(),
    ) {
        require(board(boardId) != null) { "保存先のボードが見つかりません" }
        require((elements.map { it.boardId } + spatialElements.map { it.boardId } +
            arrows.map { it.boardId } + inkStrokes.map { it.boardId }).all { it == boardId }) {
            "別ボードの要素が含まれています"
        }
        clearElements(boardId)
        clearSpatialElements(boardId)
        clearArrows(boardId)
        clearInkStrokes(boardId)
        putElements(elements)
        putSpatialElements(spatialElements)
        putArrows(arrows)
        putInkStrokes(inkStrokes)
        setUpdatedAt(boardId, nextUpdateTime())
    }

    @Transaction
    suspend fun deleteBoard(boardId: Long): Boolean {
        if (board(boardId) == null) return false
        clearElements(boardId)
        clearSpatialElements(boardId)
        clearArrows(boardId)
        clearInkStrokes(boardId)
        return removeBoard(boardId) == 1
    }
}

@Database(
    entities = [BoardRow::class, TextElementRow::class, SpatialElementRow::class,
        ArrowElementRow::class, InkStrokeRow::class],
    version = 2,
    exportSchema = true,
)
abstract class CanvasDatabase : RoomDatabase() {
    abstract fun canvasDao(): CanvasDao

    companion object {
        val MIGRATION_1_2 = Migration(1, 2) { connection ->
            connection.execSQL("""CREATE TABLE IF NOT EXISTS `ink_strokes` (
                `id` TEXT NOT NULL, `boardId` INTEGER NOT NULL, `groupId` TEXT NOT NULL,
                `sequence` INTEGER NOT NULL, `kind` TEXT NOT NULL, `startedAt` INTEGER NOT NULL,
                `endedAt` INTEGER NOT NULL, `inputType` TEXT NOT NULL, `inputs` BLOB NOT NULL,
                PRIMARY KEY(`id`))""".trimIndent())
        }

        fun open(context: Context): CanvasDatabase = Room.databaseBuilder(
            context.applicationContext,
            CanvasDatabase::class.java,
            "thinkcanvas.db",
        ).setDriver(AndroidSQLiteDriver()).addMigrations(MIGRATION_1_2).build()
    }
}
