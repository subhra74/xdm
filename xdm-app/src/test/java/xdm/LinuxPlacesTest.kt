package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun removableMountsFromMount_bsdOutput() {
        val freebsd = """
            /dev/ada0p2 on / (ufs, local, journaled soft-updates)
            devfs on /dev (devfs)
            /dev/da0s1 on /media/USB STICK (msdosfs, local)
            /dev/da3s1 on /media/USB (1) (msdosfs, local)
            nas:/export on /mnt/nas (nfs)
            /dev/da1p1 on /mnt/data (ufs, local)
            /dev/da2p1 on /mnt/data/deeper (ufs, local)
            tmpfs on /tmp (tmpfs, local)
        """.trimIndent()
        assertEquals(
            listOf(File("/media/USB STICK"), File("/media/USB (1)"), File("/mnt/nas"), File("/mnt/data")),
            LinuxPlaces.removableMountsFromMount(freebsd, "a"),
            "FreeBSD and DragonFly: dev on /point (fstype, flags)",
        )
        val openbsd = """
            /dev/sd0a on / type ffs (local)
            /dev/sd1i on /mnt/backup type ffs (local, nodev, nosuid)
            /dev/wd1a on /media/disk type ffs (local)
        """.trimIndent()
        assertEquals(
            listOf(File("/mnt/backup"), File("/media/disk")),
            LinuxPlaces.removableMountsFromMount(openbsd, "a", typed = true),
            "OpenBSD and NetBSD: dev on /point type fstype (flags)",
        )
    }

    @Test
    fun removableMountsFromMount_folderNamedLikeTheTypeColumn() {
        val freebsd = "/dev/da0s1 on /media/disk type x (msdosfs, local)"
        assertEquals(
            listOf(File("/media/disk type x")),
            LinuxPlaces.removableMountsFromMount(freebsd, "a"),
            "FreeBSD has no type column, so ' type x' belongs to the folder",
        )
        val openbsd = "/dev/sd1i on /mnt/disk type x type ffs (local, nodev)"
        assertEquals(
            listOf(File("/mnt/disk type x")),
            LinuxPlaces.removableMountsFromMount(openbsd, "a", typed = true),
            "OpenBSD: only the last ' type ' is the column",
        )
    }

    @Test
    fun printsTypeAfterPoint_onlyOpenAndNetBsd() {
        listOf("OpenBSD", "NetBSD").forEach { assertTrue(LinuxPlaces.printsTypeAfterPoint(it), it) }
        listOf("FreeBSD", "DragonFly", "MidnightBSD", "Linux", null).forEach {
            assertFalse(LinuxPlaces.printsTypeAfterPoint(it), it.toString())
        }
    }

    @Test
    fun removableMountsFor_linuxNeverRunsMount() {
        val mountinfo = "90 22 8:17 / /mnt/a rw - ext4 /dev/sdb1 rw"
        assertEquals(
            listOf(File("/mnt/a")),
            LinuxPlaces.removableMountsFor(false, "u", false, { mountinfo }, { error("mount(8) on Linux") }),
        )
        assertEquals(
            emptyList<File>(),
            LinuxPlaces.removableMountsFor(false, "u", false, { null }, { error("mount(8) on Linux") }),
            "no mountinfo on Linux is no drives, not a fallback",
        )
    }

    @Test
    fun removableMountsFor_bsdNeverReadsMountinfo() {
        val output = "/dev/da0s1 on /mnt/usb (msdosfs, local)"
        assertEquals(
            listOf(File("/mnt/usb")),
            LinuxPlaces.removableMountsFor(true, "u", false, { error("mountinfo on a BSD") }, { output }),
        )
        assertEquals(
            emptyList<File>(),
            LinuxPlaces.removableMountsFor(true, "u", false, { error("mountinfo on a BSD") }, { null }),
        )
    }

    @Test
    fun unescapeMount_octalEscapes() {
        assertEquals("a b\tc\\d", LinuxPlaces.unescapeMount("a\\040b\\011c\\134d"), "space, tab, backslash")
        assertEquals("plain", LinuxPlaces.unescapeMount("plain"), "nothing to do")
    }
}
