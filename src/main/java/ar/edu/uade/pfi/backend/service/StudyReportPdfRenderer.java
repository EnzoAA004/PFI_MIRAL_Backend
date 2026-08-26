package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.dto.LongitudinalComparisonResultDto;
import ar.edu.uade.pfi.backend.dto.StudyReportDto;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

@Component
public class StudyReportPdfRenderer {
    private static final String TITLE = "Informe técnico de análisis de RM lumbar";

    public byte[] render(StudyReportDto report) {
        try (PDDocument document = new PDDocument()) {
            List<String> lines = linesFor(report);
            PDType1Font titleFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font bodyFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage page = null;
            PDPageContentStream content = null;
            float y = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (page == null || y < 54) {
                    if (content != null) content.close();
                    page = new PDPage(PDRectangle.A4);
                    document.addPage(page);
                    content = new PDPageContentStream(document, page);
                    y = 790;
                    content.beginText();
                    content.setFont(i == 0 ? titleFont : bodyFont, i == 0 ? 16 : 10);
                    content.newLineAtOffset(48, y);
                } else if (i == 1) {
                    content.setFont(bodyFont, 10);
                }
                content.showText(printable(lines.get(i)));
                content.newLineAtOffset(0, i == 0 ? -24 : -15);
                y -= i == 0 ? 24 : 15;
            }
            if (content != null) {
                content.endText();
                content.close();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not render study report PDF", e);
        }
    }

    private List<String> linesFor(StudyReportDto report) {
        List<String> lines = new ArrayList<>();
        lines.add(TITLE);
        lines.add(report.safety().disclaimer());
        lines.add("");
        lines.add("Estudio: " + report.caseId());
        lines.add("Estado del reporte: " + report.reportStatus());
        lines.add("Fecha de estudio: " + value(report.studyDate()));
        lines.add("Modalidad: " + value(report.modality()));
        lines.add("");
        lines.add("Resumen del análisis");
        lines.add("Run: " + value(report.analysis().analysisRunId()));
        lines.add("Estado: " + value(report.analysis().analysisStatus()));
        lines.add("Release: " + value(report.analysis().releaseId()));
        lines.add("Revisión humana requerida: " + yesNo(report.analysis().humanReviewRequired()));
        lines.add("");
        lines.add("Estado de revisión");
        lines.add("Estado: " + value(report.review().reviewStatus()));
        lines.add("Comentario: " + value(report.review().reviewComment()));
        lines.add("");
        lines.add("Niveles");
        if (report.discLevels().isEmpty()) {
            lines.add("Sin niveles disponibles para una corrida completada o parcial.");
        } else {
            for (StudyReportDto.DiscLevelSummary level : report.discLevels()) {
                lines.add(value(level.level()) + " | localización=" + value(level.localizationStatus())
                    + " | axial=" + value(level.axialPairingStatus())
                    + " | revisión=" + value(level.reviewStatus()));
            }
        }
        lines.add("");
        lines.add("Mediciones efectivas");
        if (report.measurements().isEmpty()) {
            lines.add("Sin mediciones efectivas disponibles.");
        } else {
            for (StudyReportDto.MeasurementSummary measurement : report.measurements()) {
                lines.add(value(measurement.level()) + " | " + value(measurement.plane()) + " | "
                    + value(measurement.structure()) + " | " + measurement.metric() + " | IA "
                    + value(measurement.automaticValue()) + " -> efectivo " + value(measurement.effectiveValue())
                    + " " + measurement.unit() + " | fuente=" + measurement.effectiveSource());
            }
        }
        if (report.longitudinalComparison() != null) {
            lines.add("");
            lines.add("Comparación longitudinal");
            LongitudinalComparisonResultDto comparison = report.longitudinalComparison();
            lines.add("Referencia: " + comparison.baselineCaseId() + " | Actual: " + comparison.currentCaseId());
            lines.add("Filas comparables: " + comparison.measurements().size());
        }
        lines.add("");
        lines.add("Warnings / limitations");
        report.warnings().forEach(warning -> lines.add("Warning: " + warning));
        report.limitations().forEach(limitation -> lines.add("Limitación: " + limitation));
        return lines;
    }

    private String value(Object value) {
        return value == null ? "No disponible" : value.toString();
    }

    private String yesNo(boolean value) {
        return value ? "sí" : "no";
    }

    private String printable(String line) {
        return line == null ? "" : line.replace('\u2192', '>');
    }
}
