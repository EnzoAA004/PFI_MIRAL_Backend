package ar.edu.uade.pfi.backend.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiEvidenceRegionTest {
    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");
    private static final Map<String, Object> VALID_BBOX = new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap();

    @Test
    void aiOriginRequiresAtLeastOneProvenanceField() {
        assertThrows(IllegalArgumentException.class, () -> new AiEvidenceRegion(
            "region-1", "run-1", null, null, "sagittal", "L4-L5", "disc", 3,
            "ai", EvidenceGeometry.TYPE_BBOX, VALID_BBOX, null,
            null, null, null, null, false, null, null, null, NOW
        ));
    }

    @Test
    void aiOriginWithAnySingleProvenanceFieldIsAccepted() {
        AiEvidenceRegion region = new AiEvidenceRegion(
            "region-1", "run-1", null, null, "sagittal", "L4-L5", "disc", 3,
            "ai", EvidenceGeometry.TYPE_BBOX, VALID_BBOX, null,
            null, null, "pfi.multiplanar-run.v2", null, false, null, null, null, NOW
        );
        assertEquals("pfi.multiplanar-run.v2", region.pipelineVersion());
    }

    @Test
    void professionalOriginNeedsNoProvenance() {
        AiEvidenceRegion region = new AiEvidenceRegion(
            "region-1", "run-1", null, null, "sagittal", "L4-L5", "disc", 3,
            "professional", EvidenceGeometry.TYPE_BBOX, VALID_BBOX, null,
            null, null, null, null, false, null, null, "dra-demo", NOW
        );
        assertEquals("professional", region.origin());
    }

    @Test
    void blankLabelFallsBackToTheNeutralDefault() {
        AiEvidenceRegion region = new AiEvidenceRegion(
            "region-1", "run-1", null, null, "sagittal", "L4-L5", "disc", 3,
            "professional", EvidenceGeometry.TYPE_BBOX, VALID_BBOX, null,
            null, null, null, null, false, "  ", null, "dra-demo", NOW
        );
        assertEquals(AiEvidenceRegion.DEFAULT_LABEL, region.label());
    }

    @Test
    void rejectsAnInvalidGeometryPayloadEagerly() {
        assertThrows(IllegalArgumentException.class, () -> new AiEvidenceRegion(
            "region-1", "run-1", null, null, "sagittal", "L4-L5", "disc", 3,
            "professional", "NOT_A_TYPE", Map.of(), null,
            null, null, null, null, false, null, null, "dra-demo", NOW
        ));
    }

    @Test
    void rejectsAnOriginOutsideAiOrProfessional() {
        assertThrows(IllegalArgumentException.class, () -> new AiEvidenceRegion(
            "region-1", "run-1", null, null, "sagittal", "L4-L5", "disc", 3,
            "radiologist-bot", EvidenceGeometry.TYPE_BBOX, VALID_BBOX, null,
            null, null, null, null, false, null, null, null, NOW
        ));
    }

    @Test
    void rejectsAConfidenceOutsideZeroToOne() {
        assertThrows(IllegalArgumentException.class, () -> new AiEvidenceRegion(
            "region-1", "run-1", null, null, "sagittal", "L4-L5", "disc", 3,
            "ai", EvidenceGeometry.TYPE_BBOX, VALID_BBOX, null,
            "model-1", null, null, 1.5, false, null, null, null, NOW
        ));
    }
}
