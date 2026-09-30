package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.EvidenceGeometry;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.CorrectEvidenceRegionRequestDto;
import ar.edu.uade.pfi.backend.dto.StudyReportDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * EXT-04B5: proves — at the unit level, without Docker — that a correction saved through
 * {@link ProfessionalAnnotationService} is immediately visible through
 * {@link StudyReportService#report}/{@code #reportPdf}, with no special-cased logic on either
 * side: both simply read whatever {@link EffectiveAnnotationResolver} already resolves. The full
 * Docker/browser loop (create → refetch → PDF) is additionally proven by the EXT-04B5 E2E harness.
 */
class ProfessionalAnnotationEndToEndReportTest {
    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");
    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final EffectiveAnnotationResolver annotationResolver = new EffectiveAnnotationResolver(repository);
    private final ProfessionalAnnotationService annotationService = new ProfessionalAnnotationService(repository, annotationResolver, Clock.fixed(NOW, ZoneOffset.UTC));
    private final EvidenceImageSource noImagesAvailable = new EvidenceImageSource() {
        @Override public Optional<byte[]> fetchSeriesSlice(String inputId, int sliceIndex) { return Optional.empty(); }
        @Override public Optional<byte[]> fetchAsset(String runId, String plane, String assetName) { return Optional.empty(); }
    };
    private final StudyReportService reportService = new StudyReportService(
        repository,
        new EffectiveAnalysisRunSelector(repository),
        new EffectiveMeasurementResolver(repository),
        annotationResolver,
        new LongitudinalComparisonService(repository, new EffectiveAnalysisRunSelector(repository), new EffectiveMeasurementResolver(repository), Clock.fixed(NOW, ZoneOffset.UTC)),
        new StudyReportPdfRenderer(noImagesAvailable, new EvidenceOverlayComposer()),
        Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void correctionMadeThroughTheAnnotationServiceIsImmediatelyReflectedInTheReport() throws Exception {
        Study study = new Study("study-1", "CASE-B5-1", "registered", "SUBJ-B5-1", LocalDate.of(2026, 9, 5), "MRI", "fixture", "medium", NOW, NOW);
        repository.saveStudy(study);
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            "run-1", study.id(), study.caseId(), "pfi.ai-product-result.v1", "ext04b5-fixture", "completed",
            true, true, false, "trace-1", "inp_fixture", null, List.of(), Map.of(), NOW, NOW
        );
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        AiEvidenceRegion region = new AiEvidenceRegion(
            "region-1", run.id(), null, null, "sagittal", "L4-L5", "disc", 6,
            "ai", EvidenceGeometry.TYPE_BBOX, new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap(), null,
            "sagittal_spider", "sha256:abc", "pfi.multiplanar-run.v2", null,
            false, null, null, null, NOW
        );
        repository.saveAiEvidenceRegion(region);

        // Before correction: AI-suggested BBOX.
        StudyReportDto before = reportService.report("CASE-B5-1", null);
        assertEquals("BBOX", before.visualEvidence().get(0).geometryType());
        assertEquals("ai_suggested", before.visualEvidence().get(0).source());

        // Professional correction, through the same service the controller calls.
        annotationService.correctRegion(
            "CASE-B5-1", "run-1", "region-1",
            new CorrectEvidenceRegionRequestDto("ELLIPSE", new EvidenceGeometry.Ellipse(20, 20, 8, 6).toMap(), "corrección de geometría"),
            "dr.professional@pfi.local"
        );

        // Report: no special-casing needed, EffectiveAnnotationResolver already picks it up.
        StudyReportDto after = reportService.report("CASE-B5-1", null);
        StudyReportDto.VisualEvidenceSummary evidence = after.visualEvidence().get(0);
        assertEquals("ELLIPSE", evidence.geometryType());
        assertEquals("professional_corrected", evidence.source());

        // PDF: the renderer only ever consumes what report() already produced.
        byte[] pdf = reportService.reportPdf("CASE-B5-1", null);
        assertTrue(new String(pdf, 0, 5).startsWith("%PDF-"));
        assertTrue(pdf.length > 500);
    }
}
