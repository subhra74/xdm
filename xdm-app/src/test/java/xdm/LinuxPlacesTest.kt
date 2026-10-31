package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.app.utils.linux.LinuxPlaces
import java.io.File

/** The sources of the Linux `JFileChooser` places bar. */
class LinuxPlacesTest {

    @Test
    fun userDirs_inFixedOrderAndWithoutHome() {
        val text = """
            # comment
            XDG_VIDEOS_DIR="${'$'}HOME/Videos"
            XDG_DOWNLOAD_DIR="${'$'}HOME/Téléchargements"
            XDG_DESKTOP_DIR="${'$'}HOME"
            XDG_TEMPLATES_DIR="${'$'}HOME/Templates"
            XDG_MUSIC_DIR="/data/music"
        """.trimIndent()
        val home = File("/home/a")
        assertEquals(
            listOf(File(home, "Téléchargements"), File("/data/music"), File(home, "Videos")),
            LinuxPlaces.userDirs(text, home),
            "download, music, videos; desktop = home and templates left out",
        )
    }

    @Test
    fun gtkBookmarks_fileOnlyWithLabels() {
        val text = "file:///home/a/My%20Work Work\nsftp://host/x\nfile:///srv/media\n"
        assertEquals(
            listOf(File("/home/a/My Work") to "Work", File("/srv/media") to null),
            LinuxPlaces.gtkBookmarks(text),
            "decoded paths, optional label",
        )
    }

    @Test
    fun removableMounts_underMediaAndMnt() {
        val mountinfo = """
            22 1 8:2 / / rw,relatime - ext4 /dev/sda2 rw
            30 22 0:25 / /proc rw - proc proc rw
            90 22 8:17 / /run/media/a/USB\040STICK rw,nosuid - vfat /dev/sdb1 rw
            91 22 8:33 / /media/a/Backup rw - ext4 /dev/sdc1 rw
            92 22 8:49 / /mnt/data rw - ntfs3 /dev/sdd1 rw
            93 22 8:65 / /run/media/b/Other rw - vfat /dev/sde1 rw
            94 22 8:66 / /mnt/data/deeper rw - ext4 /dev/sde2 rw
        """.trimIndent()
        assertEquals(
            listOf(File("/run/media/a/USB STICK"), File("/media/a/Backup"), File("/mnt/data")),
            LinuxPlaces.removableMounts(mountinfo, "a"),
            "our drives only, escapes decoded",
        )
    }

    @Test
    fun unescapeMount_octalEscapes() {
        assertEquals("a b\tc\\d", LinuxPlaces.unescapeMount("a\\040b\\011c\\134d"), "space, tab, backslash")
        assertEquals("plain", LinuxPlaces.unescapeMount("plain"), "nothing to do")
    }
}
