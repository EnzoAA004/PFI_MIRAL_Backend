package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.EvidenceGeometry;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceRegionIngestionServiceTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-03T10:00:00Z"), ZoneOffset.UTC);

    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final EvidenceRegionIngestionService service = new EvidenceRegionIngestionService(repository, FIXED_CLOCK);

    @Test
    void ingestsAiGeometryAlreadyNormalizedAndPersistsIt() {
        AiProductAnalysisRun run = seedRun("run-1");
        Map<String, Object> geometry = new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap();

        AiEvidenceRegion region = service.ingestAiGeometry(new EvidenceRegionIngestionService.IngestAiGeometryRequest(
            run.id(), null, null, "sagittal", "L4-L5", "disc", 5,
            EvidenceGeometry.TYPE_BBOX, geometry, null,
            "model-sagittal", "sha256:abc", "pfi.multiplanar-run.v2", null,
            false, null, null, "not_requested", false
        ));

        assertEquals("ai", region.origin());
        assertEquals(FIXED_CLOCK.instant(), region.createdAt());
        assertEquals(1, repository.findAiEvidenceRegionsByAnalysisRunId(run.id()).size());
    }

    @Test
    void refusesToIngestGeometryFromBlockedP10_7() {
        AiProductAnalysisRun run = seedRun("run-1");
        Map<String, Object> geometry = new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap();

        assertThrows(IllegalArgumentException.class, () -> service.ingestAiGeometry(new EvidenceRegionIngestionService.IngestAiGeometryRequest(
            run.id(), null, null, "sagittal", "L4-L5", "disc", 5,
            EvidenceGeometry.TYPE_BBOX, geometry, null,
            "model-degenerative", "sha256:abc", "pfi.degenerative-findings.v1", null,
            false, null, null, "blocked_preprocessing_parity", false
        )));
        assertTrue(repository.findAiEvidenceRegionsByAnalysisRunId(run.id()).isEmpty());
    }

    @Test
    void refusesToIngest67CGeometryNotFlaggedExperimental() {
        AiProductAnalysisRun run = seedRun("run-1");
        Map<String, Object> geometry = new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap();

        assertThrows(IllegalArgumentException.class, () -> service.ingestAiGeometry(new EvidenceRegionIngestionService.IngestAiGeometryRequest(
            run.id(), null, null, "axial", "L4-L5", "disc", 5,
            EvidenceGeometry.TYPE_BBOX, geometry, null,
            "model-axial-pairing", "sha256:def", "pfi.multiplanar-run.v2", null,
            false, null, null, null, true
        )));
    }

    @Test
    void allows67CGeometryWhenExplicitlyFlaggedExperimental() {
        AiProductAnalysisRun run = seedRun("run-1");
        Map<String, Object> geometry = new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap();

        AiEvidenceRegion region = service.ingestAiGeometry(new EvidenceRegionIngestionService.IngestAiGeometryRequest(
            run.id(), null, null, "axial", "L4-L5", "disc", 5,
            EvidenceGeometry.TYPE_BBOX, geometry, null,
            "model-axial-pairing", "sha256:def", "pfi.multiplanar-run.v2", null,
            true, null, null, null, true
        ));

        assertTrue(region.experimental());
    }

    private AiProductAnalysisRun seedRun(String runId) {
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            runId, "study-1", "CASE-1", "pfi.ai-product-result.v1", "release-1",
            "completed", true, true, false, "trace-1", "input-sagittal", "input-axial",
            List.of(), Map.of(), FIXED_CLOCK.instant(), FIXED_CLOCK.instant()
        );
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        return run;
    }
}
