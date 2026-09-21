package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ar.edu.uade.pfi.backend.domain.EvidenceGeometry;
import ar.edu.uade.pfi.backend.dto.StudyReportDto;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * EXT-04B4: PDF-level tests. Follows the same PDFBox-text-extraction convention
 * {@code StudyReportServiceTest#rendersPdfWithTitleSafetyTextAndStructuredSections} already
 * established — no OCR, no headful rendering. Image embedding is verified by walking each page's
 * XObject resources and counting {@link PDImageXObject} entries (never just trusting a byte
 * count), which is enough to prove an image was actually embedded without rendering the page to
 * a bitmap.
 */
class StudyReportPdfRendererTest {
    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final String REAL_INPUT_ID = "inp_2822bf9640ab40a289050a30ce2fe6fd";

    /** Real, decodable 40x40 PNG — small enough to keep tests fast, large enough to exercise real scaling math. */
    private static byte[] fakeSlicePng() {
        try {
            BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Resolves only `REAL_INPUT_ID`/sliceIndex 6 and asset ("run-ok","sagittal","mask-preview.png") — everything else is a 404/unavailable, exercising the fallback path. */
    private static final EvidenceImageSource FAKE_SOURCE = new EvidenceImageSource() {
        @Override
        public Optional<byte[]> fetchSeriesSlice(String inputId, int sliceIndex) {
            return REAL_INPUT_ID.equals(inputId) && sliceIndex == 6 ? Optional.of(fakeSlicePng()) : Optional.empty();
        }

        @Override
        public Optional<byte[]> fetchAsset(String runId, String plane, String assetName) {
            return "run-ok".equals(runId) && "sagittal".equals(plane) && "mask-preview.png".equals(assetName)
                ? Optional.of(fakeSlicePng()) : Optional.empty();
        }
    };

    private final StudyReportPdfRenderer renderer = new StudyReportPdfRenderer(FAKE_SOURCE, new EvidenceOverlayComposer());

    // ---- fixtures --------------------------------------------------------------------------

    private StudyReportDto.VisualEvidenceSummary evidence(String geometryType, Map<String, Object> geometry, boolean experimental, StudyReportDto.BaseImageRef baseImage, String source) {
        return new StudyReportDto.VisualEvidenceSummary(
            "region-1", "sagittal", "L4-L5", "disc", geometryType, geometry, source,
            "pending", "Región de interés",
            "ai_suggested".equals(source) ? "Sugerencia de IA sin revisión profesional." : null,
            experimental, baseImage
        );
    }

    private StudyReportDto.BeforeAfterEvidencePairDto pair(StudyReportDto.VisualEvidenceSummary previous, StudyReportDto.VisualEvidenceSummary current, String previousSource) {
        return new StudyReportDto.BeforeAfterEvidencePairDto(
            "L4-L5", "sagittal", "disc", "majorAxisMm", "mm",
            LocalDate.of(2026, 2, 10), LocalDate.of(2026, 3, 10),
            12.3, 12.9, 0.6, 4.878,
            previousSource, "ai_original",
            previous, current
        );
    }

    private StudyReportDto report(List<StudyReportDto.VisualEvidenceSummary> visualEvidence, List<StudyReportDto.BeforeAfterEvidencePairDto> beforeAfterEvidence) {
        return new StudyReportDto(
            "pfi.study-report.v1", NOW, "ready", "CASE-PDF-04B4", LocalDate.of(2026, 3, 10), "MRI",
            new StudyReportDto.AnalysisSummary("run-1", "completed", "release", NOW, NOW, true),
            new StudyReportDto.ReviewSummary("accepted", null, null),
            List.of(), List.of(), List.of(), List.of("disclaimer"),
            null,
            new StudyReportDto.LongitudinalHistorySummary(
                !beforeAfterEvidence.isEmpty(),
                new StudyReportDto.CurrentStudySummary("CASE-PDF-04B4", LocalDate.of(2026, 3, 10), "run-1"),
                List.of(),
                List.of(),
                beforeAfterEvidence.isEmpty() ? "No existen estudios previos comparables disponibles." : "Historial disponible."
            ),
            visualEvidence,
            beforeAfterEvidence,
            new StudyReportDto.SafetySummary(true, true, false, "Este informe corresponde a un prototipo de apoyo al análisis y no constituye un diagnóstico médico autónomo."),
            new StudyReportDto.TraceabilitySummary("CASE-PDF-04B4", "run-1", "pfi.ai-product-result.v1", "release", NOW)
        );
    }

    private String textOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private int imageXObjectCount(byte[] pdf) throws Exception {
        int count = 0;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            for (var page : document.getPages()) {
                for (var name : page.getResources().getXObjectNames()) {
                    var xobject = page.getResources().getXObject(name);
                    if (xobject instanceof PDImageXObject) count++;
                }
            }
        }
        return count;
    }

    // ---- 1. sin visual evidence --------------------------------------------------------

    @Test
    void pdfWithoutVisualEvidenceStillGeneratesAndShowsTheNeutralEmptyMessage() throws Exception {
        byte[] pdf = renderer.render(report(List.of(), List.of()));
        assertTrue(new String(pdf, 0, 5).startsWith("%PDF-"));
        assertTrue(textOf(pdf).contains("No hay evidencia visual disponible para este análisis."));
    }

    // ---- 2/3/4. BBOX / ELLIPSE / POLYGON -------------------------------------------------

    @Test
    void pdfEmbedsARealImageForABboxRegion() throws Exception {
        var item = evidence("BBOX", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap(), false, new StudyReportDto.BaseImageRef(REAL_INPUT_ID, 6), "ai_suggested");
        byte[] pdf = renderer.render(report(List.of(item), List.of()));
        assertEquals(1, imageXObjectCount(pdf), "expected exactly one embedded image for one BBOX region");
        assertTrue(textOf(pdf).contains("Región sugerida por IA"));
    }

    @Test
    void pdfEmbedsARealImageForAnEllipseRegion() throws Exception {
        var item = evidence("ELLIPSE", new EvidenceGeometry.Ellipse(20, 20, 8, 6).toMap(), false, new StudyReportDto.BaseImageRef(REAL_INPUT_ID, 6), "professional_corrected");
        byte[] pdf = renderer.render(report(List.of(item), List.of()));
        assertEquals(1, imageXObjectCount(pdf));
        assertTrue(textOf(pdf).contains("Región corregida por profesional"));
    }

    @Test
    void pdfEmbedsARealImageForAPolygonRegion() throws Exception {
        var geometry = new EvidenceGeometry.Polygon(List.of(
            new EvidenceGeometry.Polygon.Point(0, 0), new EvidenceGeometry.Polygon.Point(10, 0), new EvidenceGeometry.Polygon.Point(5, 10)
        )).toMap();
        var item = evidence("POLYGON", geometry, false, new StudyReportDto.BaseImageRef(REAL_INPUT_ID, 6), "professional_drawn");
        byte[] pdf = renderer.render(report(List.of(item), List.of()));
        assertEquals(1, imageXObjectCount(pdf));
        assertTrue(textOf(pdf).contains("Región creada por profesional"));
    }

    @Test
    void pdfEmbedsTheAssetImageForAMaskReferenceRegion() throws Exception {
        Map<String, Object> geometry = new EvidenceGeometry.MaskReference("run-ok", "sagittal", "mask-preview.png").toMap();
        var item = evidence("MASK_REFERENCE", geometry, false, null, "ai_validated");
        byte[] pdf = renderer.render(report(List.of(item), List.of()));
        assertEquals(1, imageXObjectCount(pdf), "MASK_REFERENCE should resolve its own asset as the base image");
    }

    // ---- 5. experimental ------------------------------------------------------------------

    @Test
    void experimentalEvidenceShowsTheExperimentalBadge() throws Exception {
        var item = evidence("BBOX", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap(), true, new StudyReportDto.BaseImageRef(REAL_INPUT_ID, 6), "ai_suggested");
        byte[] pdf = renderer.render(report(List.of(item), List.of()));
        assertTrue(textOf(pdf).contains("Experimental"));
        assertTrue(textOf(pdf).contains("Sugerencia de IA sin revisión profesional."));
    }

    // ---- 6. rejected no aparece (renderer only ever sees what StudyReportService already
    // filtered to effective — StudyReportServiceTest covers the filtering itself; here we only
    // confirm the renderer never reintroduces a region on its own) ------------------------

    @Test
    void rendererNeverAddsARegionThatWasNotInTheEffectiveList() throws Exception {
        byte[] pdf = renderer.render(report(List.of(), List.of()));
        assertEquals(0, imageXObjectCount(pdf));
    }

    // ---- 7. base image missing fallback ---------------------------------------------------

    @Test
    void missingBaseImageFallsBackToTextWithoutCrashing() throws Exception {
        var item = evidence("BBOX", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap(), false, null, "ai_suggested");
        byte[] pdf = assertDoesNotThrow(() -> renderer.render(report(List.of(item), List.of())));
        assertEquals(0, imageXObjectCount(pdf));
        assertTrue(textOf(pdf).contains("Imagen base no disponible."));
    }

    // ---- 8. image slice failure fallback ---------------------------------------------------

    @Test
    void unresolvableSliceFallsBackToTextWithoutCrashing() throws Exception {
        var item = evidence("BBOX", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap(), false, new StudyReportDto.BaseImageRef("inp_doesnotexist00000000000000000000", 6), "ai_suggested");
        byte[] pdf = assertDoesNotThrow(() -> renderer.render(report(List.of(item), List.of())));
        assertEquals(0, imageXObjectCount(pdf));
        assertTrue(textOf(pdf).contains("Imagen base no disponible."));
    }

    // ---- 9/10. before/after -----------------------------------------------------------------

    @Test
    void beforeAfterWithTwoImagesEmbedsBothSideBySide() throws Exception {
        var previous = evidence("BBOX", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap(), false, new StudyReportDto.BaseImageRef(REAL_INPUT_ID, 6), "professional_corrected");
        var current = evidence("BBOX", new EvidenceGeometry.Bbox(11, 11, 20, 20).toMap(), false, new StudyReportDto.BaseImageRef(REAL_INPUT_ID, 6), "ai_suggested");
        byte[] pdf = renderer.render(report(List.of(current), List.of(pair(previous, current, "professional_correction"))));
        assertEquals(3, imageXObjectCount(pdf), "1 for the standalone 'Evidencia visual' item + 2 for the before/after pair");
        String text = textOf(pdf);
        assertTrue(text.contains("Comparación con estudio previo"));
        assertTrue(text.contains("12.3") && text.contains("12.9"));
        assertTrue(text.contains("+0.6"));
    }

    @Test
    void beforeAfterWithoutAPreviousImageShowsTheTextualFallbackAndStillShowsNumbers() throws Exception {
        byte[] pdf = renderer.render(report(List.of(), List.of(pair(null, null, "ai_original"))));
        String text = textOf(pdf);
        assertTrue(text.contains("Sin imagen previa comparable."));
        assertTrue(text.contains("12.3") && text.contains("12.9"));
    }

    // ---- 11. fechas -------------------------------------------------------------------------

    @Test
    void beforeAfterShowsExplicitFormattedDates() throws Exception {
        byte[] pdf = renderer.render(report(List.of(), List.of(pair(null, null, "ai_original"))));
        String text = textOf(pdf);
        assertTrue(text.contains("10/02/2026"), "expected dd/MM/yyyy previous date");
        assertTrue(text.contains("10/03/2026"), "expected dd/MM/yyyy current date");
    }

    // ---- 12. effective measurement corregido -------------------------------------------------

    @Test
    void correctedValueShowsTheNeutralCorrectedMarker() throws Exception {
        byte[] pdf = renderer.render(report(List.of(), List.of(pair(null, null, "professional_correction"))));
        assertTrue(textOf(pdf).contains("(corregido)"));
    }

    // ---- 13. no clinical wording --------------------------------------------------------------

    @Test
    void neverUsesForbiddenClinicalWording() throws Exception {
        var item = evidence("BBOX", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap(), true, new StudyReportDto.BaseImageRef(REAL_INPUT_ID, 6), "ai_suggested");
        byte[] pdf = renderer.render(report(List.of(item), List.of(pair(item, item, "professional_correction"))));
        // The mandatory safety disclaimer itself says "no constituye un diagnóstico médico
        // autónomo" — legitimate, required, negating language — so it is stripped before
        // scanning for a clinical CLAIM the renderer might have added on its own.
        String lower = textOf(pdf).replace("Este informe corresponde a un prototipo de apoyo al análisis y no constituye un diagnóstico médico autónomo.", "").toLowerCase();
        Pattern forbidden = Pattern.compile("patolog|diagnóstic|diagnostic|lesión confirmada|mejor[oó]|empeor[oó]|progres|deterior");
        assertFalse(forbidden.matcher(lower).find(), "PDF must never use clinical/diagnostic wording beyond the mandatory disclaimer");
    }

    // ---- 14. no sensitive data ------------------------------------------------------------

    @Test
    void neverLeaksReviewerPathsOrHosts() throws Exception {
        Map<String, Object> geometry = new EvidenceGeometry.MaskReference("run-ok", "sagittal", "mask-preview.png").toMap();
        var item = evidence("MASK_REFERENCE", geometry, false, null, "ai_validated");
        byte[] pdf = renderer.render(report(List.of(item), List.of()));
        String text = textOf(pdf);
        for (String forbidden : List.of("dr.reviewer@pfi.local", "C:\\", "/var/", "http://", "run-ok/sagittal")) {
            assertFalse(text.contains(forbidden), "PDF must never contain: " + forbidden);
        }
    }

    // ---- 15/16. header + minimum size ------------------------------------------------------

    @Test
    void pdfHasTheCorrectHeaderAndAReasonableMinimumSize() throws Exception {
        var item = evidence("BBOX", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap(), false, new StudyReportDto.BaseImageRef(REAL_INPUT_ID, 6), "ai_suggested");
        byte[] pdf = renderer.render(report(List.of(item), List.of()));
        assertAll(
            () -> assertTrue(new String(pdf, 0, 5).startsWith("%PDF-")),
            () -> assertTrue(pdf.length > 2000, "a PDF carrying one embedded image should exceed a trivial byte size")
        );
    }

    // ---- 17/18. multiple pages / multiple evidence items without crashing -----------------

    @Test
    void manyEvidenceItemsSpanMultiplePagesWithoutCuttingAnImageInHalf() throws Exception {
        List<StudyReportDto.VisualEvidenceSummary> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(evidence("BBOX", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap(), false, new StudyReportDto.BaseImageRef(REAL_INPUT_ID, 6), "ai_suggested"));
        }
        byte[] pdf = assertDoesNotThrow(() -> renderer.render(report(many, List.of())));
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertTrue(document.getNumberOfPages() > 1, "12 embedded images should not all fit on a single A4 page");
        }
        assertEquals(12, imageXObjectCount(pdf), "every one of the 12 images must still be embedded, none dropped across the page break");
    }
}
