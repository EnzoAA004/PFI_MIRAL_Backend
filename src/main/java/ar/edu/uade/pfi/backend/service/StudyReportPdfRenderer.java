package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.EvidenceGeometry;
import ar.edu.uade.pfi.backend.dto.LongitudinalComparisonResultDto;
import ar.edu.uade.pfi.backend.dto.StudyReportDto;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

/**
 * EXT-04B4: the same PDFBox-based renderer this product has used since Stage 88, extended with
 * raster image support (base slice + geometry overlay, composed in memory by
 * {@link EvidenceOverlayComposer}) for the "Evidencia visual" and "Comparación con estudio
 * previo" sections. All pre-existing text sections are unchanged in content and order — only the
 * internal representation moved from a flat {@code List<String>} to a small {@link Block} model
 * so an image can reserve vertical space and trigger a page break the same way a text line
 * already does, never splitting an image across two pages (Phase 11).
 *
 * <p>Every image fetch/composition failure (missing base image, 404 slice, invalid geometry,
 * undecodable asset) is caught here and degrades to a text fallback line — never a thrown
 * exception, so a report with N evidence items never fails to generate over one bad image
 * (Phase 12). No filesystem path is ever read; both {@link EvidenceImageSource} methods proxy
 * through the same already-validated, already-tested {@code AiBackendService} calls the REST
 * endpoints use, just in-process rather than over HTTP.
 */
@Component
public class StudyReportPdfRenderer {
    private static final String TITLE = "Informe técnico de análisis de RM lumbar";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final float MARGIN = 48f;
    private static final float BOTTOM_MARGIN = 54f;
    private static final float TITLE_LINE_HEIGHT = 24f;
    private static final float BODY_LINE_HEIGHT = 15f;
    private static final float IMAGE_ROW_HEIGHT = 170f;
    private static final float IMAGE_ROW_GAP = 12f;

    private final EvidenceImageSource imageSource;
    private final EvidenceOverlayComposer overlayComposer;

    public StudyReportPdfRenderer(EvidenceImageSource imageSource, EvidenceOverlayComposer overlayComposer) {
        this.imageSource = imageSource;
        this.overlayComposer = overlayComposer;
    }

    private sealed interface Block permits TextBlock, ImageRowBlock {
    }

    private record TextBlock(String text, boolean title) implements Block {
    }

    /** 1 image (a single evidence item) or 2 (a before/after pair) — never split across a page break. */
    private record ImageRowBlock(List<byte[]> images) implements Block {
    }

    public byte[] render(StudyReportDto report) {
        List<Block> blocks = blocksFor(report);
        try (PDDocument document = new PDDocument()) {
            PDType1Font titleFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font bodyFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            float contentWidth = PDRectangle.A4.getWidth() - 2 * MARGIN;

            PageState state = new PageState(document);
            for (Block block : blocks) {
                if (block instanceof TextBlock text) {
                    float lineHeight = text.title() ? TITLE_LINE_HEIGHT : BODY_LINE_HEIGHT;
                    state.ensureSpace(lineHeight);
                    try (PDPageContentStream content = new PDPageContentStream(document, state.page, PDPageContentStream.AppendMode.APPEND, true)) {
                        content.beginText();
                        content.setFont(text.title() ? titleFont : bodyFont, text.title() ? 16 : 10);
                        content.newLineAtOffset(MARGIN, state.y);
                        content.showText(printable(text.text()));
                        content.endText();
                    }
                    state.y -= lineHeight;
                } else if (block instanceof ImageRowBlock row) {
                    state.ensureSpace(IMAGE_ROW_HEIGHT);
                    drawImageRow(document, state, row.images(), contentWidth);
                    state.y -= IMAGE_ROW_HEIGHT + 10;
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not render study report PDF", e);
        }
    }

    /** Tracks the current page/cursor across the whole render loop; starts pages lazily on demand. */
    private final class PageState {
        private final PDDocument document;
        private PDPage page;
        private float y;

        PageState(PDDocument document) {
            this.document = document;
        }

        void ensureSpace(float needed) throws IOException {
            if (page == null || y - needed < BOTTOM_MARGIN) {
                page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                y = PDRectangle.A4.getHeight() - 52;
            }
        }
    }

    private void drawImageRow(PDDocument document, PageState state, List<byte[]> images, float contentWidth) throws IOException {
        float gap = images.size() > 1 ? IMAGE_ROW_GAP : 0f;
        float colWidth = (contentWidth - gap * (images.size() - 1)) / images.size();
        try (PDPageContentStream content = new PDPageContentStream(document, state.page, PDPageContentStream.AppendMode.APPEND, true)) {
            float x = MARGIN;
            for (byte[] png : images) {
                PDImageXObject image = PDImageXObject.createFromByteArray(document, png, "evidence");
                float scale = Math.min(colWidth / image.getWidth(), IMAGE_ROW_HEIGHT / image.getHeight());
                float drawWidth = image.getWidth() * scale;
                float drawHeight = image.getHeight() * scale;
                // Top-align within the row's fixed height box so two differently-shaped images
                // (e.g. a wider MASK_REFERENCE asset next to a square slice) still line up.
                content.drawImage(image, x, state.y - drawHeight, drawWidth, drawHeight);
                x += colWidth + gap;
            }
        }
    }

    private List<Block> blocksFor(StudyReportDto report) {
        List<Block> blocks = new ArrayList<>();
        title(blocks, TITLE);
        line(blocks, report.safety().disclaimer());
        blank(blocks);
        line(blocks, "Estudio: " + report.caseId());
        line(blocks, "Estado del reporte: " + report.reportStatus());
        line(blocks, "Fecha de estudio: " + value(report.studyDate()));
        line(blocks, "Modalidad: " + value(report.modality()));
        blank(blocks);
        line(blocks, "Resumen del análisis");
        line(blocks, "Run: " + value(report.analysis().analysisRunId()));
        line(blocks, "Estado: " + value(report.analysis().analysisStatus()));
        line(blocks, "Release: " + value(report.analysis().releaseId()));
        line(blocks, "Revisión humana requerida: " + yesNo(report.analysis().humanReviewRequired()));
        blank(blocks);
        line(blocks, "Estado de revisión");
        line(blocks, "Estado: " + value(report.review().reviewStatus()));
        line(blocks, "Comentario: " + value(report.review().reviewComment()));
        blank(blocks);
        line(blocks, "Niveles");
        if (report.discLevels().isEmpty()) {
            line(blocks, "Sin niveles disponibles para una corrida completada o parcial.");
        } else {
            for (StudyReportDto.DiscLevelSummary level : report.discLevels()) {
                line(blocks, value(level.level()) + " | localización=" + value(level.localizationStatus())
                    + " | axial=" + value(level.axialPairingStatus())
                    + " | revisión=" + value(level.reviewStatus()));
            }
        }
        blank(blocks);
        line(blocks, "Mediciones efectivas");
        if (report.measurements().isEmpty()) {
            line(blocks, "Sin mediciones efectivas disponibles.");
        } else {
            for (StudyReportDto.MeasurementSummary measurement : report.measurements()) {
                line(blocks, value(measurement.level()) + " | " + value(measurement.plane()) + " | "
                    + value(measurement.structure()) + " | " + measurement.metric() + " | IA "
                    + value(measurement.automaticValue()) + " -> efectivo " + value(measurement.effectiveValue())
                    + " " + measurement.unit() + " | fuente=" + measurement.effectiveSource());
            }
        }
        if (report.longitudinalComparison() != null) {
            blank(blocks);
            line(blocks, "Comparación longitudinal");
            LongitudinalComparisonResultDto comparison = report.longitudinalComparison();
            line(blocks, "Referencia: " + comparison.baselineCaseId() + " | Actual: " + comparison.currentCaseId());
            line(blocks, "Filas comparables: " + comparison.measurements().size());
        }
        blank(blocks);
        line(blocks, "Evolución / comparación con estudios previos");
        StudyReportDto.LongitudinalHistorySummary history = report.longitudinalHistory();
        if (history == null || history.previousStudies().isEmpty()) {
            line(blocks, "No existen estudios previos comparables disponibles.");
        } else if (history.comparisons().isEmpty()) {
            line(blocks, "Existen estudios previos, pero no hay métricas comparables disponibles.");
        } else {
            line(blocks, "Fecha | Nivel | Estructura | Métrica | Anterior | Actual | Delta");
            for (StudyReportDto.LongitudinalHistoryComparison item : history.comparisons()) {
                line(blocks, value(item.previousStudyDate()) + " | " + value(item.level()) + " | "
                    + value(item.structure()) + " | " + value(item.metric()) + " | "
                    + value(item.previousEffectiveValue()) + " " + value(item.unit()) + " | "
                    + value(item.currentEffectiveValue()) + " " + value(item.unit()) + " | "
                    + signed(item.absoluteDelta()) + " " + value(item.unit())
                    + (item.percentageDelta() == null ? "" : " (" + signed(item.percentageDelta()) + "%)"));
            }
        }

        beforeAfterSection(blocks, report);
        visualEvidenceSection(blocks, report);

        blank(blocks);
        line(blocks, "Warnings / limitations");
        report.warnings().forEach(warning -> line(blocks, "Warning: " + warning));
        report.limitations().forEach(limitation -> line(blocks, "Limitación: " + limitation));
        return blocks;
    }

    // ---- EXT-04B4: "Evidencia visual" -----------------------------------------------------

    private void visualEvidenceSection(List<Block> blocks, StudyReportDto report) {
        blank(blocks);
        line(blocks, "Evidencia visual");
        if (report.visualEvidence().isEmpty()) {
            line(blocks, EvidenceReportText.NO_VISUAL_EVIDENCE_MESSAGE);
            return;
        }
        for (StudyReportDto.VisualEvidenceSummary item : report.visualEvidence()) {
            line(blocks, EvidenceReportText.displaySource(item.source()) + (item.experimental() ? " · " + EvidenceReportText.EXPERIMENTAL_BADGE : ""));
            line(blocks, (value(item.level())) + " · " + EvidenceReportText.displayPlane(item.plane())
                + (item.structure() != null ? " · " + item.structure() : "") + " · " + EvidenceReportText.displayReviewStatus(item.reviewStatus()));
            line(blocks, item.label());
            if (item.warning() != null) line(blocks, item.warning());
            Optional<byte[]> composed = composedImageFor(item);
            if (composed.isPresent()) {
                blocks.add(new ImageRowBlock(List.of(composed.get())));
            } else {
                line(blocks, EvidenceReportText.NO_BASE_IMAGE_MESSAGE);
            }
        }
    }

    // ---- EXT-04B4: "Comparación con estudio previo" ----------------------------------------

    private void beforeAfterSection(List<Block> blocks, StudyReportDto report) {
        if (report.beforeAfterEvidence().isEmpty()) return;
        blank(blocks);
        line(blocks, EvidenceReportText.BEFORE_AFTER_TITLE);
        for (StudyReportDto.BeforeAfterEvidencePairDto pair : report.beforeAfterEvidence()) {
            line(blocks, value(pair.level()) + " · " + EvidenceReportText.displayPlane(pair.plane())
                + (pair.structure() != null ? " · " + pair.structure() : "") + " · " + value(pair.metric()));
            line(blocks, "Estudio previo: " + formattedDate(pair.previousStudyDate()) + "   Estudio actual: " + formattedDate(pair.currentStudyDate()));

            List<byte[]> images = new ArrayList<>();
            if (pair.previousEvidence() != null) composedImageFor(pair.previousEvidence()).ifPresent(images::add);
            if (pair.currentEvidence() != null) composedImageFor(pair.currentEvidence()).ifPresent(images::add);
            if (images.isEmpty()) {
                line(blocks, "Sin imagen previa comparable.");
            } else {
                blocks.add(new ImageRowBlock(List.copyOf(images)));
            }

            line(blocks, "Valor previo: " + valueWithSourceMarker(pair.previousEffectiveValue(), pair.unit(), pair.previousValueSource())
                + "   Valor actual: " + valueWithSourceMarker(pair.currentEffectiveValue(), pair.unit(), pair.currentValueSource()));
            line(blocks, "Delta: " + signed(pair.absoluteDelta()) + " " + value(pair.unit())
                + (pair.percentageDelta() == null ? "" : "   Delta %: " + signed(pair.percentageDelta()) + "%"));
        }
    }

    private String valueWithSourceMarker(Double value, String unit, String source) {
        String base = value == null ? "No disponible" : value + " " + value(unit);
        return "professional_correction".equals(source) ? base + " (corregido)" : base;
    }

    private String formattedDate(LocalDate date) {
        return date == null ? "No disponible" : date.format(DATE_FORMAT);
    }

    // ---- EXT-04B4: image resolution + composition, always fail-safe -----------------------

    /**
     * Never throws: an unparseable geometry, a missing baseImage, a 404 slice/asset, or a
     * decode failure all resolve to {@link Optional#empty()} — the caller falls back to a text
     * line, and the rest of the PDF (and the rest of this one item's own text lines) is
     * unaffected (Phase 12).
     */
    private Optional<byte[]> composedImageFor(StudyReportDto.VisualEvidenceSummary item) {
        try {
            EvidenceGeometry geometry = EvidenceGeometry.fromMap(item.geometryType(), item.geometry());
            Optional<byte[]> baseBytes;
            if (geometry instanceof EvidenceGeometry.MaskReference mask) {
                baseBytes = imageSource.fetchAsset(mask.runId(), mask.plane(), mask.assetName());
            } else if (item.baseImage() != null) {
                baseBytes = imageSource.fetchSeriesSlice(item.baseImage().inputId(), item.baseImage().sliceIndex());
            } else {
                baseBytes = Optional.empty();
            }
            if (baseBytes.isEmpty()) return Optional.empty();
            return Optional.of(overlayComposer.composePng(baseBytes.get(), geometry));
        } catch (RuntimeException | IOException ex) {
            return Optional.empty();
        }
    }

    // ---- text helpers (unchanged behavior from Stage 88) -----------------------------------

    private void title(List<Block> blocks, String text) {
        blocks.add(new TextBlock(text, true));
    }

    private void line(List<Block> blocks, String text) {
        blocks.add(new TextBlock(text, false));
    }

    private void blank(List<Block> blocks) {
        blocks.add(new TextBlock("", false));
    }

    private String value(Object value) {
        return value == null ? "No disponible" : value.toString();
    }

    private String yesNo(boolean value) {
        return value ? "sí" : "no";
    }

    private String signed(Double value) {
        if (value == null) return "No disponible";
        if (value > 0) return "+" + value;
        if (value == 0) return "±0.0";
        return value.toString();
    }

    private String printable(String line) {
        return line == null ? "" : line.replace('→', '>');
    }
}
