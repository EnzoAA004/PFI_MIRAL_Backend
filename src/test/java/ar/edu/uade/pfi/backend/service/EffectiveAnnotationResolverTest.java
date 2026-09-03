package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegionCorrection;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.DerivedEvidenceOrigin;
import ar.edu.uade.pfi.backend.domain.EvidenceGeometry;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EffectiveAnnotationResolverTest {
    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");
    private static final Map<String, Object> AI_BBOX = new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap();
    private static final Map<String, Object> CORRECTED_BBOX = new EvidenceGeometry.Bbox(12, 12, 18, 18).toMap();
    private static final Map<String, Object> DRAWN_POLYGON = new EvidenceGeometry.Polygon(List.of(
        new EvidenceGeometry.Polygon.Point(0, 0),
        new EvidenceGeometry.Polygon.Point(10, 0),
        new EvidenceGeometry.Polygon.Point(5, 10)
    )).toMap();

    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final EffectiveAnnotationResolver resolver = new EffectiveAnnotationResolver(repository);

    @Test
    void aiOriginPreservesModelProvenance() {
        AiProductAnalysisRun run = seedRun("run-1");
        AiEvidenceRegion region = aiRegion("region-1", run.id(), null, "sagittal", "L4-L5", "disc");
        repository.saveAiEvidenceRegion(region);

        var annotations = resolver.effectiveAnnotations(run, true);

        assertEquals(1, annotations.size());
        assertEquals("model-sagittal", annotations.get(0).region().modelId());
        assertEquals("sha256:abc", annotations.get(0).region().modelSha256());
        assertEquals("pfi.multiplanar-run.v2", annotations.get(0).region().pipelineVersion());
    }

    @Test
    void professionalOriginNeedsNoAiProvenanceAndIsAlwaysEffective() {
        AiProductAnalysisRun run = seedRun("run-1");
        AiEvidenceRegion region = professionalRegion("region-1", run.id(), null);
        repository.saveAiEvidenceRegion(region);

        var annotation = resolver.effectiveAnnotations(run, false).get(0);

        assertEquals(DerivedEvidenceOrigin.PROFESSIONAL_DRAWN, annotation.derivedOrigin());
        assertTrue(annotation.effective());
    }

    @Test
    void appendOnlyCorrectionPreservesTheOriginalAiGeometryUnmodified() {
        AiProductAnalysisRun run = seedRun("run-1");
        AiEvidenceRegion region = aiRegion("region-1", run.id(), null, "sagittal", "L4-L5", "disc");
        repository.saveAiEvidenceRegion(region);
        repository.saveAiEvidenceRegionCorrection(new AiEvidenceRegionCorrection(
            "correction-1", region.id(), run.id(),
            EvidenceGeometry.TYPE_BBOX, AI_BBOX, EvidenceGeometry.TYPE_BBOX, CORRECTED_BBOX,
            "ajuste de límites", "dra-demo", NOW
        ));

        var annotation = resolver.effectiveAnnotations(run, true).get(0);

        // The root row itself -- the AI's original -- is untouched.
        assertEquals(AI_BBOX, region.geometry());
        assertEquals(repository.findAiEvidenceRegionsByAnalysisRunId(run.id()).get(0).geometry(), AI_BBOX);
        // But the EFFECTIVE geometry the resolver surfaces is the corrected one.
        assertEquals(CORRECTED_BBOX, annotation.effectiveGeometry());
        assertEquals(DerivedEvidenceOrigin.PROFESSIONAL_CORRECTED, annotation.derivedOrigin());
        assertTrue(annotation.effective());
    }

    @Test
    void latestOfMultipleCorrectionsWins() {
        AiProductAnalysisRun run = seedRun("run-1");
        AiEvidenceRegion region = aiRegion("region-1", run.id(), null, "sagittal", "L4-L5", "disc");
        repository.saveAiEvidenceRegion(region);
        repository.saveAiEvidenceRegionCorrection(new AiEvidenceRegionCorrection(
            "correction-1", region.id(), run.id(), EvidenceGeometry.TYPE_BBOX, AI_BBOX,
            EvidenceGeometry.TYPE_BBOX, CORRECTED_BBOX, "primer ajuste", "dra-demo", NOW
        ));
        Map<String, Object> secondCorrection = new EvidenceGeometry.Bbox(13, 13, 17, 17).toMap();
        repository.saveAiEvidenceRegionCorrection(new AiEvidenceRegionCorrection(
            "correction-2", region.id(), run.id(), EvidenceGeometry.TYPE_BBOX, CORRECTED_BBOX,
            EvidenceGeometry.TYPE_BBOX, secondCorrection, "segundo ajuste", "dra-demo", NOW.plusSeconds(60)
        ));

        var annotation = resolver.effectiveAnnotations(run, true).get(0);

        assertEquals(secondCorrection, annotation.effectiveGeometry());
    }

    @Test
    void rejectedAiEvidenceWithoutCorrectionIsNotEffective() {
        AiProductAnalysisRun run = seedRun("run-1");
        AiEvidenceRegion region = aiRegion("region-1", run.id(), null, "sagittal", "L4-L5", "disc");
        repository.saveAiEvidenceRegion(region);
        repository.upsertAiProductAnalysisRunReview(run.id(), "rejected", "no es una región válida", "dra-demo", NOW);

        var annotation = resolver.effectiveAnnotations(run, true).get(0);

        assertEquals(DerivedEvidenceOrigin.AI_SUGGESTED, annotation.derivedOrigin());
        assertFalse(annotation.effective());
    }

    @Test
    void aCorrectionAfterRejectionBecomesEffectiveAsAProfessionalCorrection() {
        AiProductAnalysisRun run = seedRun("run-1");
        AiEvidenceRegion region = aiRegion("region-1", run.id(), null, "sagittal", "L4-L5", "disc");
        repository.saveAiEvidenceRegion(region);
        repository.upsertAiProductAnalysisRunReview(run.id(), "rejected", "geometría original mal ubicada", "dra-demo", NOW);
        repository.saveAiEvidenceRegionCorrection(new AiEvidenceRegionCorrection(
            "correction-1", region.id(), run.id(), EvidenceGeometry.TYPE_BBOX, AI_BBOX,
            EvidenceGeometry.TYPE_BBOX, CORRECTED_BBOX, "reubicada por el profesional", "dra-demo", NOW.plusSeconds(10)
        ));

        var annotation = resolver.effectiveAnnotations(run, true).get(0);

        assertEquals(DerivedEvidenceOrigin.PROFESSIONAL_CORRECTED, annotation.derivedOrigin());
        assertTrue(annotation.effective());
    }

    @Test
    void acceptedAiEvidenceIsEffectiveWithoutAnyCorrection() {
        AiProductAnalysisRun run = seedRun("run-1");
        AiEvidenceRegion region = aiRegion("region-1", run.id(), null, "sagittal", "L4-L5", "disc");
        repository.saveAiEvidenceRegion(region);
        repository.upsertAiProductAnalysisRunReview(run.id(), "accepted", "confirmado", "dra-demo", NOW);

        var annotation = resolver.effectiveAnnotations(run, true).get(0);

        assertEquals(DerivedEvidenceOrigin.PROFESSIONAL_VALIDATED, annotation.derivedOrigin());
        assertTrue(annotation.effective());
        assertEquals(AI_BBOX, annotation.effectiveGeometry());
    }

    @Test
    void observedAiEvidenceIsTreatedTheSameAsAccepted() {
        AiProductAnalysisRun run = seedRun("run-1");
        AiEvidenceRegion region = aiRegion("region-1", run.id(), null, "sagittal", "L4-L5", "disc");
        repository.saveAiEvidenceRegion(region);
        repository.upsertAiProductAnalysisRunReview(run.id(), "observed", "revisado, sin objeciones", "dra-demo", NOW);

        var annotation = resolver.effectiveAnnotations(run, true).get(0);

        assertEquals(DerivedEvidenceOrigin.PROFESSIONAL_VALIDATED, annotation.derivedOrigin());
        assertTrue(annotation.effective());
    }

    @Test
    void unreviewedAiSuggestionIsEffectiveOnlyWhenTheConsumerAllowsSuggestedEvidence() {
        AiProductAnalysisRun run = seedRun("run-1");
        AiEvidenceRegion region = aiRegion("region-1", run.id(), null, "sagittal", "L4-L5", "disc");
        repository.saveAiEvidenceRegion(region);

        var allowed = resolver.effectiveAnnotations(run, true).get(0);
        var blocked = resolver.effectiveAnnotations(run, false).get(0);

        assertEquals(DerivedEvidenceOrigin.AI_SUGGESTED, allowed.derivedOrigin());
        assertTrue(allowed.effective());
        assertEquals(DerivedEvidenceOrigin.AI_SUGGESTED, blocked.derivedOrigin());
        assertFalse(blocked.effective());
    }

    @Test
    void experimentalFlagSurvivesIntoTheEffectiveAnnotation() {
        AiProductAnalysisRun run = seedRun("run-1");
        AiEvidenceRegion region = new AiEvidenceRegion(
            "region-1", run.id(), null, null, "axial", "L4-L5", "disc", 5,
            "ai", EvidenceGeometry.TYPE_BBOX, AI_BBOX, null,
            "model-axial-pairing", "sha256:def", "pfi.multiplanar-run.v2", null,
            true, null, "derivado de emparejamiento axial experimental (67C)", null, NOW
        );
        repository.saveAiEvidenceRegion(region);

        var annotation = resolver.effectiveAnnotations(run, true).get(0);

        assertTrue(annotation.region().experimental());
    }

    @Test
    void discLevelReviewTakesPrecedenceOverRunLevelReviewWhenBothExist() {
        AiProductAnalysisRun run = seedRun("run-1");
        AiDiscLevelResult discLevel = new AiDiscLevelResult(
            "level-1", run.id(), 0, "L4-L5", "localized", "ai", true, false,
            "not_required", true, false, false, null, true
        );
        repository.saveAiProductAnalysisRun(run, List.of(discLevel), List.of());
        AiEvidenceRegion region = aiRegion("region-1", run.id(), "level-1", "sagittal", "L4-L5", "disc");
        repository.saveAiEvidenceRegion(region);
        repository.upsertAiProductAnalysisRunReview(run.id(), "rejected", "run global no confiable", "dra-demo", NOW);
        repository.upsertAiDiscLevelReview("level-1", "accepted", "este nivel puntual sí", "dra-demo", NOW);

        var annotation = resolver.effectiveAnnotations(run, true).get(0);

        assertEquals("accepted", annotation.reviewStatus());
        assertEquals(DerivedEvidenceOrigin.PROFESSIONAL_VALIDATED, annotation.derivedOrigin());
        assertTrue(annotation.effective());
    }

    private AiProductAnalysisRun seedRun(String runId) {
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            runId, "study-1", "CASE-1", "pfi.ai-product-result.v1", "release-1",
            "completed", true, true, false, "trace-1", "input-sagittal", "input-axial",
            List.of(), Map.of(), NOW, NOW.plusSeconds(2)
        );
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        return run;
    }

    private AiEvidenceRegion aiRegion(String id, String runId, String discLevelResultId, String plane, String level, String structure) {
        return new AiEvidenceRegion(
            id, runId, discLevelResultId, null, plane, level, structure, 5,
            "ai", EvidenceGeometry.TYPE_BBOX, AI_BBOX, null,
            "model-sagittal", "sha256:abc", "pfi.multiplanar-run.v2", null,
            false, null, null, null, NOW
        );
    }

    private AiEvidenceRegion professionalRegion(String id, String runId, String discLevelResultId) {
        return new AiEvidenceRegion(
            id, runId, discLevelResultId, null, "sagittal", "L4-L5", "disc", 5,
            "professional", EvidenceGeometry.TYPE_POLYGON, DRAWN_POLYGON, null,
            null, null, null, null, false, null, "dibujada a mano por el profesional", "dra-demo", NOW
        );
    }
}
