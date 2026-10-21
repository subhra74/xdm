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

    // --- Single HTTP downloads: a missing extension is added, an existing one is never replaced ----
    @Test fun singleHttpUrlNameGetsImageExt() =
        assertEquals("photo.jpg", getFileName("http://h/photo?id=1", null, "image/jpeg"), "missing ext added")

    @Test fun singleHttpDispositionNameGetsPdfExt() = assertEquals(
        "report.pdf", getFileName("http://h/get?id=1", "attachment; filename=\"report\"", "application/pdf"),
        "Content-Disposition name without ext gets one",
    )

    @Test fun singleHttpExistingExtNeverReplaced() =
        assertEquals("a.jpg", getFileName("http://h/a.jpg", null, "image/webp"), "single HTTP keeps .jpg")

    @Test fun singleHttpHtmlStillGetsHtml() =
        assertEquals("x.exe.html", getFileName("http://h/x.exe", null, "text/html"), "a login page is still .html")

    @Test fun singleHttpKeptNameGetsPdfExt() =
        assertEquals("report.pdf", getFileName("report", true, "http://h/get?id=1", null, "application/pdf"),
            "kept name without ext gets one")

    @Test fun singleHttpKeptNameExtNeverReplaced() =
        assertEquals("cover.jpg", getFileName("cover.jpg", true, "http://h/c", null, "image/png"),
            "kept name keeps its ext")

    @Test fun singleHttpKeptNameRedirectedToHtml() =
        assertEquals("x.exe.html", getFileName("x.exe", true, "http://h/login", null, "text/html"),
            "a kept name that turned into a page still gets .html")
}
