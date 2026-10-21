package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.core.util.fixExtension
import xdm.core.util.getFileName

/** [fixExtension]: a batch file's extension from the server's Content-Type. */
class TestMimeTypes {

    // --- Added when missing ---------------------------------------------------------------------
    @Test fun jpegWithoutExtGetsJpg() = assertEquals("photo.jpg", fixExtension("photo", "image/jpeg"))
    @Test fun pngWithoutExtGetsPng() = assertEquals("photo.png", fixExtension("photo", "image/png"))
    @Test fun pdfWithoutExtGetsPdf() = assertEquals("report.pdf", fixExtension("report", "application/pdf"))
    @Test fun docWithoutExtGetsDoc() = assertEquals("letter.doc", fixExtension("letter", "application/msword"))
    @Test fun docxWithoutExtGetsDocx() = assertEquals(
        "letter.docx",
        fixExtension("letter", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    )
    @Test fun parametersAndCaseIgnored() = assertEquals("a.png", fixExtension("a", "Image/PNG; charset=binary"))
    @Test fun scriptExtGetsTypeAppended() = assertEquals("img.php.jpg", fixExtension("img.php", "image/jpeg"))
    @Test fun numericSuffixIsNotAnExt() = assertEquals("IMG.08.01.jpg", fixExtension("IMG.08.01", "image/jpeg"))

    // --- Kept -------------------------------------------------------------------------------------
    @Test fun matchingExtKept() = assertEquals("a.jpg", fixExtension("a.jpg", "image/jpeg"))
    @Test fun aliasExtKept() = assertEquals("a.JPEG", fixExtension("a.JPEG", "image/jpeg"))
    @Test fun genericTypeLeavesNameAlone() = assertEquals("photo", fixExtension("photo", "application/octet-stream"))
    @Test fun textPlainLeavesNameAlone() = assertEquals("notes", fixExtension("notes", "text/plain"))
    @Test fun noTypeLeavesNameAlone() = assertEquals("photo", fixExtension("photo", null))
    @Test fun unknownTypeLeavesNameAlone() = assertEquals("x.bin", fixExtension("x.bin", "application/x-whatever"))
    @Test fun otherKindExtKept() = assertEquals("app.jar", fixExtension("app.jar", "application/zip"))
    @Test fun docxServedAsZipKept() = assertEquals("a.docx", fixExtension("a.docx", "application/zip"))

    // --- Replaced within the same kind ------------------------------------------------------------
    @Test fun jpgServedAsWebpReplaced() = assertEquals("a.webp", fixExtension("a.jpg", "image/webp"))
    @Test fun noReplaceOnlyAdds() = assertEquals("a.jpg", fixExtension("a.jpg", "image/webp", replace = false))
    @Test fun noReplaceStillAddsMissing() = assertEquals("a.png", fixExtension("a", "image/png", replace = false))

    // --- Single HTTP downloads keep their naming: the fix is batch-only ---------------------------
    @Test fun singleHttpNameNotGivenImageExt() =
        assertEquals("photo", getFileName("http://h/photo?id=1", null, "image/jpeg"), "single HTTP name unchanged")

    @Test fun singleHttpKeptNameNotGivenPdfExt() =
        assertEquals("report", getFileName("report", true, "http://h/get?id=1", null, "application/pdf"),
            "single HTTP kept name unchanged")
}
