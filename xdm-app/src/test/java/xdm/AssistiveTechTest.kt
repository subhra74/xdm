package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.utils.AssistiveTech
import java.io.File
import java.nio.file.Files

/**
 * An assistive technology this runtime can't load is dropped before AWT starts (issue 1456: the Java
 * Access Bridge named in ~/.accessibility.properties stopped the trimmed runtime with "Failed to launch JVM").
 */
class AssistiveTechTest {
    private lateinit var dir: File
    private var saved: String? = null

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-a11y").toFile()
        saved = System.getProperty(AssistiveTech.PROPERTY)
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
        if (saved == null) System.clearProperty(AssistiveTech.PROPERTY) else System.setProperty(AssistiveTech.PROPERTY, saved!!)
    }

    private fun file(name: String, text: String?) = File(dir, name).apply { if (text != null) writeText(text) }

    @Test
    fun propertyWinsOverFiles() {
        val user = file("user", "assistive_technologies=a.B\n")
        val runtime = file("runtime", "assistive_technologies=c.D\n")
        assertEquals("x.Y", AssistiveTech.configuredNames("x.Y", user, runtime), "system property first")
        assertEquals("", AssistiveTech.configuredNames("", user, runtime), "an empty property still overrides the files")
    }

    @Test
    fun userFileThenRuntimeFile() {
        val runtime = file("runtime", "assistive_technologies=c.D\n")
        assertEquals("a.B", AssistiveTech.configuredNames(null, file("user", "assistive_technologies=a.B\n"), runtime), "user file")
        assertEquals("c.D", AssistiveTech.configuredNames(null, file("missing", null), runtime), "no user file: runtime file")
        assertEquals("c.D", AssistiveTech.configuredNames(null, file("empty", ""), runtime), "empty user file: runtime file")
        assertNull(AssistiveTech.configuredNames(null, file("other", "screen_magnifier_present=true\n"), runtime),
            "a user file with other keys hides the runtime file, as in AWT")
        assertNull(AssistiveTech.configuredNames(null, file("none1", null), file("none2", null)), "nothing configured")
    }

    @Test
    fun parseSplitsLikeAwt() {
        assertEquals(listOf("a.B", "c.D"), AssistiveTech.parse(" a.B ,, c.D,a.B , "), "trimmed, no blanks or repeats")
    }

    @Test
    fun keepsOnlyProviderNames() {
        val bridge = "com.sun.java.accessibility.AccessBridge"
        assertEquals(listOf(bridge) to listOf("foo.Bar"), AssistiveTech.split(listOf(bridge, "foo.Bar"), setOf(bridge)),
            "the provider is kept, the unknown name dropped")
        assertEquals(listOf("foo.Bar") to emptyList<String>(), AssistiveTech.split(listOf("foo.Bar"), setOf("foo.Bar", bridge)),
            "nothing dropped when every name is a provider")
        assertEquals(emptyList<String>() to listOf(javaClass.name), AssistiveTech.split(listOf(javaClass.name), emptySet()),
            "a loadable class that isn't a provider is dropped: no class lookup")
    }

    @Test
    fun blankPropertyLeftAlone() {
        System.setProperty(AssistiveTech.PROPERTY, " , ")
        assertEquals(emptyList<String>(), AssistiveTech.sanitize(), "nothing to drop")
        assertEquals(" , ", System.getProperty(AssistiveTech.PROPERTY), "unchanged")
    }

    @Test
    fun allMissingLeavesEmptyProperty() {
        System.setProperty(AssistiveTech.PROPERTY, "no.such.Technology")
        assertEquals(listOf("no.such.Technology"), AssistiveTech.sanitize(), "dropped")
        assertEquals("", System.getProperty(AssistiveTech.PROPERTY), "empty: AWT loads nothing")
    }
}
