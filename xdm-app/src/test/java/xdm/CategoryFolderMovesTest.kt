package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.app.utils.categoryFolderMoves
import xdm.manager.DownloadCategory
import java.io.File

/** Moving category folders along with the default download folder. */
class CategoryFolderMovesTest {
    private val old = File("/data/old").absolutePath
    private val new = File("/data/new").absolutePath

    private fun cat(id: String, folder: String) = DownloadCategory(id, id, emptySet(), folder = folder)
    private fun path(vararg parts: String) = parts.fold(File("/")) { f, p -> File(f, p) }.absolutePath

    @Test
    fun builtInsUnderTheOldFolder_moveToTheSamePlaceUnderTheNewOne() {
        val moves = categoryFolderMoves(DownloadCategory.defaults(old), old, new)
        assertEquals(DownloadCategory.defaults(new).associate { it.id to it.folder }, moves)
    }

    @Test
    fun theOldFolderItself_andNestedFolders_move() {
        val moves = categoryFolderMoves(
            listOf(cat("a", old), cat("b", path("data", "old", "x", "y"))), old, new,
        )
        assertEquals(mapOf("a" to new, "b" to path("data", "new", "x", "y")), moves)
    }

    @Test
    fun foldersOutsideTheOldFolder_stay() {
        val moves = categoryFolderMoves(
            listOf(cat("a", path("elsewhere", "Video")), cat("b", path("data", "older", "Video")), cat("c", "")),
            old, new,
        )
        assertEquals(emptyMap<String, String>(), moves, "sibling prefix, other folder and blank all stay")
    }

    @Test
    fun trailingSeparatorsAndDotSegments_areNormalized() {
        val moves = categoryFolderMoves(listOf(cat("a", "$old/./Video/")), "$old/", new)
        assertEquals(mapOf("a" to path("data", "new", "Video")), moves)
    }

    @Test
    fun sameOrBlankFolder_movesNothing() {
        val cats = DownloadCategory.defaults(old)
        assertEquals(emptyMap<String, String>(), categoryFolderMoves(cats, old, "$old/"), "same folder")
        assertEquals(emptyMap<String, String>(), categoryFolderMoves(cats, old, " "), "blank new folder")
    }

    @Test
    fun newFolderInsideTheOld_leavesCategoriesAlreadyThere() {
        val inner = path("data", "old", "xdm")
        val moves = categoryFolderMoves(
            listOf(cat("a", path("data", "old", "Video")), cat("b", path("data", "old", "xdm", "Music"))), old, inner,
        )
        assertEquals(mapOf("a" to path("data", "old", "xdm", "Video")), moves, "b is already under the new folder")
    }

    @Test
    fun newFolderAboveTheOld_stillMovesUp() {
        val outer = path("data")
        val moves = categoryFolderMoves(listOf(cat("a", path("data", "old", "Video"))), old, outer)
        assertEquals(mapOf("a" to path("data", "Video")), moves)
    }
}
