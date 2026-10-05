package com.thinkcanvas.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import androidx.sqlite.execSQL
import com.thinkcanvas.board.duplicated
import com.thinkcanvas.canvas.ArrowElement
import com.thinkcanvas.canvas.ArrowEnd
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.ShapeElement
import com.thinkcanvas.canvas.ShapeKind
import com.thinkcanvas.canvas.TextElement
import com.thinkcanvas.canvas.ImageElement
import com.thinkcanvas.canvas.modelLogicalBounds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

class CanvasDatabaseTest {
    private fun image() = ImageElement(assetId = "8c0a9b01-94fd-404c-944d-62c1249b4d01",
        x = 10f, y = -20f, width = 200f, height = 100f,
        intrinsicWidth = 400, intrinsicHeight = 200, altText = "猫の写真")

    @Test fun imageReceiptRollbackIsAtomicAndCommittedImportCannotReplayAfterUndo() { runBlocking {
        val file = Files.createTempFile("think-canvas-image-receipt-", ".db").toFile()
        file.delete()
        fun open() = Room.databaseBuilder<CanvasDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        val image = image()
        val row = ImageElementRow.fromModel(1, image)
        val receipt = ShareImportReceiptRow("image-request", 1, image.id)
        open().also { database -> database.canvasDao().putBoard(BoardRow()); database.close() }
        val raw = BundledSQLiteDriver().open(file.absolutePath)
        raw.execSQL("CREATE TRIGGER fail_image_receipt BEFORE INSERT ON share_import_receipts BEGIN SELECT RAISE(ABORT, 'forced'); END")
        raw.close()
        val failing = open()
        assertTrue(runCatching { failing.canvasDao().replaceAllForShare(receipt, emptyList(),
            emptyList(), emptyList(), images = listOf(row)) }.isFailure)
        assertTrue(failing.canvasDao().images(1).isEmpty())
        assertEquals(null, failing.canvasDao().shareReceipt(receipt.requestId))
        failing.close()
        val repair = BundledSQLiteDriver().open(file.absolutePath)
        repair.execSQL("DROP TRIGGER fail_image_receipt")
        repair.close()
        val database = open()
        val dao = database.canvasDao()
        dao.replaceAllForShare(receipt, emptyList(), emptyList(), emptyList(), images = listOf(row))
        assertEquals(image, dao.images(1).single().toModel())
        dao.replaceAll(1, emptyList(), emptyList(), emptyList())
        dao.replaceAllForShare(receipt, emptyList(), emptyList(), emptyList(), images = listOf(row))
        assertTrue(dao.images(1).isEmpty())
        assertEquals(receipt, dao.shareReceipt(receipt.requestId))
        database.close()
        file.delete()
    } }

    @Test fun duplicateReopenAndDeletePreserveTheOtherBoardsImmutableAssetReference() { runBlocking {
        val file = Files.createTempFile("think-canvas-image-copy-", ".db").toFile()
        file.delete()
        fun open() = Room.databaseBuilder<CanvasDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        val image = image()
        val source = BoardSnapshot(images = listOf(image), arrows = listOf(
            ArrowElement(from = ArrowEnd.Attached(image.id, 1f, .5f), to = ArrowEnd.Free(300f, 30f))))
        val first = open()
        val original = first.canvasDao().createBoardWithSnapshot("元", source)
        val copy = first.canvasDao().createBoardWithSnapshot("複製", source.duplicated())
        val copied = first.canvasDao().images(copy.id).single().toModel()
        assertNotEquals(image.id, copied.id)
        assertEquals(image.assetId, copied.assetId)
        assertEquals(copied.id, (first.canvasDao().arrows(copy.id).single().toModel().from as ArrowEnd.Attached).targetId)
        assertTrue(first.canvasDao().deleteBoard(original.id))
        first.close()
        val reopened = open()
        assertEquals(listOf(image.assetId), reopened.canvasDao().allImageAssetIds())
        assertEquals(copied, reopened.canvasDao().images(copy.id).single().toModel())
        reopened.close()
        file.delete()
    } }

    @Test fun editsAlwaysMoveTheBoardToTheTopEvenWithinOneMillisecond() { runBlocking {
        val file = Files.createTempFile("think-canvas-order-", ".db").toFile()
        file.delete()
        val database = Room.databaseBuilder<CanvasDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        val dao = database.canvasDao()
        val first = dao.createBoard("一", updatedAt = 100)
        val second = dao.createBoard("二", updatedAt = 100)
        assertEquals(second.id, dao.boards().first().id)
        assertEquals(1, dao.renameBoard(first.id, "更新", updatedAt = 100))
        assertEquals(first.id, dao.boards().first().id)
        dao.replaceAll(second.id, emptyList(), emptyList(), emptyList())
        assertEquals(second.id, dao.boards().first().id)
        database.close()
        file.delete()
    } }

    @Test
    fun versionOneMigrationPreservesExistingBoardAndElements() { runBlocking {
        val file = Files.createTempFile("think-canvas-migration-", ".db").toFile()
        file.delete()
        val legacy = BundledSQLiteDriver().open(file.absolutePath)
        legacy.execSQL("CREATE TABLE boards (id INTEGER NOT NULL, name TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
        legacy.execSQL("CREATE TABLE text_elements (id TEXT NOT NULL, boardId INTEGER NOT NULL, text TEXT NOT NULL, kind TEXT NOT NULL, color TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, PRIMARY KEY(id))")
        legacy.execSQL("CREATE TABLE spatial_elements (id TEXT NOT NULL, boardId INTEGER NOT NULL, kind TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, width REAL NOT NULL, height REAL NOT NULL, color TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(id))")
        legacy.execSQL("CREATE TABLE arrow_elements (id TEXT NOT NULL, boardId INTEGER NOT NULL, fromTargetId TEXT, fromU REAL, fromV REAL, fromX REAL, fromY REAL, toTargetId TEXT, toU REAL, toV REAL, toX REAL, toY REAL, bend REAL NOT NULL, PRIMARY KEY(id))")
        legacy.execSQL("INSERT INTO boards VALUES (1, '既存ボード', 123)")
        legacy.execSQL("INSERT INTO text_elements VALUES ('text', 1, '既存の考え', 'BODY', 'INK', 12.5, -8.0)")
        legacy.execSQL("PRAGMA user_version = 1")
        legacy.close()

        val database = Room.databaseBuilder<CanvasDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(CanvasDatabase.MIGRATION_1_2, CanvasDatabase.MIGRATION_2_3, CanvasDatabase.MIGRATION_3_4)
            .build()
        assertEquals("既存ボード", database.canvasDao().board(1)?.name)
        assertEquals("既存の考え", database.canvasDao().elements(1).single().text)
        assertEquals(12.5f, database.canvasDao().elements(1).single().x)
        assertEquals(emptyList<InkStrokeRow>(), database.canvasDao().inkStrokes(1))
        assertEquals(null, database.canvasDao().shareReceipt("request"))
        database.close()
        file.delete()
    } }

    @Test
    fun versionTwoAndThreeMigrationsPreserveAllTablesAndExistingReceipt() { for (version in 2..3) runBlocking {
        val file = Files.createTempFile("think-canvas-migration-v2-", ".db").toFile()
        file.delete()
        val legacy = BundledSQLiteDriver().open(file.absolutePath)
        legacy.execSQL("CREATE TABLE boards (id INTEGER NOT NULL, name TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
        legacy.execSQL("CREATE TABLE text_elements (id TEXT NOT NULL, boardId INTEGER NOT NULL, text TEXT NOT NULL, kind TEXT NOT NULL, color TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, PRIMARY KEY(id))")
        legacy.execSQL("CREATE TABLE spatial_elements (id TEXT NOT NULL, boardId INTEGER NOT NULL, kind TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, width REAL NOT NULL, height REAL NOT NULL, color TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(id))")
        legacy.execSQL("CREATE TABLE arrow_elements (id TEXT NOT NULL, boardId INTEGER NOT NULL, fromTargetId TEXT, fromU REAL, fromV REAL, fromX REAL, fromY REAL, toTargetId TEXT, toU REAL, toV REAL, toX REAL, toY REAL, bend REAL NOT NULL, PRIMARY KEY(id))")
        legacy.execSQL("CREATE TABLE ink_strokes (id TEXT NOT NULL, boardId INTEGER NOT NULL, groupId TEXT NOT NULL, sequence INTEGER NOT NULL, kind TEXT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER NOT NULL, inputType TEXT NOT NULL, inputs BLOB NOT NULL, PRIMARY KEY(id))")
        legacy.execSQL("INSERT INTO boards VALUES (1, 'v2 board', 123)")
        legacy.execSQL("INSERT INTO text_elements VALUES ('text', 1, '本文', 'BODY', 'INK', 12.5, -8.0)")
        legacy.execSQL("INSERT INTO spatial_elements VALUES ('shape', 1, 'RECTANGLE', 1, 2, 30, 40, 'INK', '')")
        legacy.execSQL("INSERT INTO arrow_elements VALUES ('arrow', 1, 'text', 0.5, 0.5, NULL, NULL, NULL, NULL, NULL, 80, 90, 0)")
        legacy.execSQL("INSERT INTO ink_strokes VALUES ('ink', 1, 'ink-group', 0, 'PEN', 1, 2, 'TOUCH', X'00')")
        if (version == 3) {
            legacy.execSQL("CREATE TABLE share_import_receipts (requestId TEXT NOT NULL, boardId INTEGER NOT NULL, elementId TEXT NOT NULL, PRIMARY KEY(requestId))")
            legacy.execSQL("INSERT INTO share_import_receipts VALUES ('request', 1, 'text')")
        }
        legacy.execSQL("PRAGMA user_version = $version")
        legacy.close()

        val database = Room.databaseBuilder<CanvasDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(CanvasDatabase.MIGRATION_1_2, CanvasDatabase.MIGRATION_2_3, CanvasDatabase.MIGRATION_3_4)
            .build()
        val dao = database.canvasDao()
        assertEquals("v2 board", dao.board(1)?.name)
        assertEquals("本文", dao.elements(1).single().text)
        assertEquals("shape", dao.spatialElements(1).single().id)
        assertEquals("arrow", dao.arrows(1).single().id)
        assertEquals("ink", dao.inkStrokes(1).single().id)
        assertEquals(if (version == 3) ShareImportReceiptRow("request", 1, "text") else null,
            dao.shareReceipt("request"))
        assertTrue(dao.images(1).isEmpty())
        database.close()
        file.delete()
    } }

    @Test
    fun shareSaveRollbackLeavesBoardContentAndReceiptUnchanged() { runBlocking {
        val file = Files.createTempFile("think-canvas-share-rollback-", ".db").toFile()
        file.delete()
        fun open() = Room.databaseBuilder<CanvasDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        val initial = open()
        val initialDao = initial.canvasDao()
        initialDao.putBoard(BoardRow())
        val before = TextElementRow("before", 1, "保存済み", "BODY", "INK", 1f, 2f)
        initialDao.replaceAll(1, listOf(before), emptyList(), emptyList())
        initial.close()

        val raw = BundledSQLiteDriver().open(file.absolutePath)
        raw.execSQL("""CREATE TRIGGER fail_share_receipt_insert
            BEFORE INSERT ON share_import_receipts
            BEGIN SELECT RAISE(ABORT, 'forced receipt failure'); END""".trimIndent())
        raw.close()

        val database = open()
        val dao = database.canvasDao()
        val receipt = ShareImportReceiptRow("request", 1, "shared")
        val shared = TextElementRow("shared", 1, "取り込み", "BODY", "INK", 3f, 4f)
        val missingElement = runCatching {
            dao.replaceAllForShare(receipt, listOf(before), emptyList(), emptyList())
        }
        assertTrue(missingElement.isFailure)
        assertEquals(listOf(before), dao.elements(1))
        assertEquals(null, dao.shareReceipt("request"))
        val result = runCatching {
            dao.replaceAllForShare(receipt, listOf(before, shared), emptyList(), emptyList())
        }
        assertTrue(result.isFailure)
        assertEquals(listOf(before), dao.elements(1))
        assertEquals(null, dao.shareReceipt("request"))
        database.close()
        file.delete()
    } }

    @Test
    fun committedShareReceiptSurvivesUndoAndPreventsOldRequestReplay() { runBlocking {
        val file = Files.createTempFile("think-canvas-share-replay-", ".db").toFile()
        file.delete()
        val database = Room.databaseBuilder<CanvasDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        val dao = database.canvasDao()
        dao.putBoard(BoardRow())
        val receipt = ShareImportReceiptRow("request", 1, "shared")
        val shared = TextElementRow("shared", 1, "重複しない", "BODY", "INK", 3f, 4f)
        dao.replaceAllForShare(receipt, listOf(shared), emptyList(), emptyList())
        assertEquals(receipt, dao.shareReceipt("request"))

        // 通常のUndo相当の全置換で要素を消しても、完了receiptは残る。
        dao.replaceAll(1, emptyList(), emptyList(), emptyList())
        val afterUndo = dao.board(1)?.updatedAt
        dao.replaceAllForShare(receipt, listOf(shared), emptyList(), emptyList())
        assertEquals(emptyList<TextElementRow>(), dao.elements(1))
        assertEquals(afterUndo, dao.board(1)?.updatedAt)
        assertEquals(receipt, dao.shareReceipt("request"))

        val mismatch = runCatching {
            dao.replaceAllForShare(receipt.copy(elementId = "other"),
                listOf(shared.copy(id = "other")), emptyList(), emptyList())
        }
        assertTrue(mismatch.isFailure)
        assertEquals(emptyList<TextElementRow>(), dao.elements(1))
        val boardMismatch = runCatching {
            dao.replaceAllForShare(receipt.copy(boardId = 2),
                listOf(shared.copy(boardId = 2)), emptyList(), emptyList())
        }
        assertTrue(boardMismatch.isFailure)
        assertEquals(emptyList<TextElementRow>(), dao.elements(1))
        assertTrue(dao.deleteBoard(1))
        assertEquals(receipt, dao.shareReceipt("request"))
        dao.replaceAllForShare(receipt, listOf(shared), emptyList(), emptyList())
        assertEquals(null, dao.board(1))
        assertEquals(emptyList<TextElementRow>(), dao.elements(1))
        database.close()
        file.delete()
    } }

    @Test
    fun identicalTextWithFreshRequestIdCommitsAsSeparateShare() { runBlocking {
        val file = Files.createTempFile("think-canvas-share-distinct-", ".db").toFile()
        file.delete()
        val database = Room.databaseBuilder<CanvasDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        val dao = database.canvasDao()
        dao.putBoard(BoardRow())
        val first = TextElementRow("element-1", 1, "同じ本文", "BODY", "INK", 1f, 2f)
        val second = TextElementRow("element-2", 1, "同じ本文", "BODY", "INK", 3f, 4f)
        val firstReceipt = ShareImportReceiptRow("request-1", 1, first.id)
        val secondReceipt = ShareImportReceiptRow("request-2", 1, second.id)
        dao.replaceAllForShare(firstReceipt, listOf(first), emptyList(), emptyList())
        dao.replaceAllForShare(secondReceipt, listOf(first, second), emptyList(), emptyList())

        assertEquals(listOf("element-1", "element-2"), dao.elements(1).map { it.id })
        assertEquals(firstReceipt, dao.shareReceipt("request-1"))
        assertEquals(secondReceipt, dao.shareReceipt("request-2"))
        database.close()
        file.delete()
    } }

    @Test
    fun committedElementsSurviveDatabaseReopenAndReplacement() {
        runBlocking {
            val file = Files.createTempFile("think-canvas-room-", ".db").toFile()
            file.delete()
            fun open() = Room.databaseBuilder<CanvasDatabase>(file.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .build()

            val first = open()
            first.canvasDao().putBoard(BoardRow())
            val initial = TextElementRow(
                id = "element-1", boardId = 1, text = "考え", kind = "BODY", color = "INK", x = 42f, y = -17f,
            )
            first.canvasDao().replaceAll(1, listOf(initial), emptyList(), emptyList())
            first.close()

            val second = open()
            val loaded = second.canvasDao().elements(1).single()
            assertEquals(initial, loaded)
            assertEquals(initial.toModel().modelLogicalBounds(), loaded.toModel().modelLogicalBounds())
            assertEquals("無題のボード", second.canvasDao().board(1)?.name)
            second.canvasDao().replaceAll(1, emptyList(), emptyList(), emptyList())
            second.close()

            val third = open()
            assertEquals(emptyList<TextElementRow>(), third.canvasDao().elements(1))
            third.close()
            file.delete()
        }
    }

    @Test
    fun shapesAndArrowsSurviveReopenWithoutChangingBoardName() { runBlocking {
        val file = Files.createTempFile("think-canvas-spatial-", ".db").toFile()
        file.delete()
        fun open() = Room.databaseBuilder<CanvasDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        val first = open()
        first.canvasDao().putBoard(BoardRow(name = "計画"))
        val shape = SpatialElementRow("shape", boardId = 1, kind = "RECTANGLE", x = 12f, y = 20f,
            width = 120f, height = 80f, color = "INK", name = "")
        val arrow = ArrowElementRow("arrow", boardId = 1, fromTargetId = "shape", fromU = 1f,
            fromV = .5f, fromX = null, fromY = null, toTargetId = null,
            toU = null, toV = null, toX = 320f, toY = 40f, bend = 15f)
        first.canvasDao().replaceAll(1, emptyList(), listOf(shape), listOf(arrow))
        first.close()

        val second = open()
        assertEquals("計画", second.canvasDao().board(1)?.name)
        assertEquals(shape, second.canvasDao().spatialElements(1).single())
        assertEquals(arrow, second.canvasDao().arrows(1).single())
        second.canvasDao().replaceAll(1, emptyList(), emptyList(), emptyList())
        assertEquals(emptyList<ArrowElementRow>(), second.canvasDao().arrows(1))
        second.close()
        file.delete()
    } }

    @Test
    fun multipleBoardsStaySeparateAcrossCopyDeleteAndReopen() { runBlocking {
        val file = Files.createTempFile("think-canvas-boards-", ".db").toFile()
        file.delete()
        fun open() = Room.databaseBuilder<CanvasDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        val original = BoardSnapshot(
            texts = listOf(TextElement(id = "source-text", text = "残す内容", x = 3f, y = 7f)),
            shapes = listOf(ShapeElement(id = "source-shape", kind = ShapeKind.RECTANGLE,
                x = 0f, y = 0f, width = 120f, height = 60f)),
            arrows = listOf(ArrowElement(id = "source-arrow",
                from = ArrowEnd.Attached("source-text", .5f, .5f),
                to = ArrowEnd.Attached("source-shape", .5f, .5f))),
        )
        val first = open()
        val dao = first.canvasDao()
        dao.putBoard(BoardRow(id = 1, name = "元"))
        dao.replaceAll(1, original.texts.map { TextElementRow.fromModel(1, it) },
            original.shapes.map { SpatialElementRow.fromModel(1, it) },
            original.arrows.map { ArrowElementRow.fromModel(1, it) })
        val copied = dao.createBoardWithSnapshot("コピー", original.duplicated())
        assertNotEquals(1L, copied.id)
        assertEquals(listOf("コピー", "元"), dao.boards().map { it.name })
        assertNotEquals(dao.elements(1).single().id, dao.elements(copied.id).single().id)
        assertEquals("source-text", dao.elements(1).single().id)
        assertTrue(dao.deleteBoard(copied.id))
        assertFalse(dao.deleteBoard(copied.id))
        val staleSave = runCatching { dao.replaceAll(copied.id, emptyList(), emptyList(), emptyList()) }
        assertTrue(staleSave.isFailure)
        val replacement = dao.createBoard("新規")
        assertNotEquals("削除済み ID を再利用しない", copied.id, replacement.id)
        assertTrue(runCatching {
            dao.replaceAll(copied.id, listOf(TextElementRow.fromModel(copied.id,
                TextElement(id = "late", text = "遅延保存", x = 0f, y = 0f))), emptyList(), emptyList())
        }.isFailure)
        assertTrue(dao.elements(replacement.id).isEmpty())
        assertEquals("残す内容", dao.elements(1).single().text)
        first.close()

        val reopened = open()
        assertEquals(setOf("元", "新規"), reopened.canvasDao().boards().map { it.name }.toSet())
        assertEquals("残す内容", reopened.canvasDao().elements(1).single().text)
        assertEquals("source-shape", reopened.canvasDao().spatialElements(1).single().id)
        assertEquals("source-arrow", reopened.canvasDao().arrows(1).single().id)
        reopened.close()
        file.delete()
    } }
}
