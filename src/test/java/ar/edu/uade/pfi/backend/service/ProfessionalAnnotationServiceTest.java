package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegionCorrection;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.EvidenceGeometry;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.CorrectEvidenceRegionRequestDto;
import ar.edu.uade.pfi.backend.dto.CreateProfessionalEvidenceRegionRequestDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** EXT-04B5: professional creation of a region + append-only correction of an existing one. */
class ProfessionalAnnotationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");
    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final EffectiveAnnotationResolver annotationResolver = new EffectiveAnnotationResolver(repository);
    private final ProfessionalAnnotationService service = new ProfessionalAnnotationService(repository, annotationResolver, Clock.fixed(NOW, ZoneOffset.UTC));

    // ---- 1. create professional BBOX ------------------------------------------------------

    @Test
    void createsAProfessionalBboxRegion() {
        AiProductAnalysisRun run = seedStudyAndRun("study-1", "CASE-1", "run-1");

        AiEvidenceRegion region = service.createProfessionalRegion(
            "CASE-1", "run-1",
            new CreateProfessionalEvidenceRegionRequestDto("sagittal", "L4-L5", "disc", 6, "BBOX", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap(), null, "nota neutral"),
            "dr.professional@pfi.local"
        );

        assertAll(
            () -> assertEquals("professional", region.origin()),
            () -> assertEquals("BBOX", region.geometryType()),
            () -> assertEquals(run.id(), region.analysisRunId()),
            () -> assertEquals("dr.professional@pfi.local", region.authoredBy()),
            () -> assertEquals(1, repository.findAiEvidenceRegionsByAnalysisRunId(run.id()).size())
        );
    }

    // ---- 2. create professional ELLIPSE ----------------------------------------------------

    @Test
    void createsAProfessionalEllipseRegion() {
        seedStudyAndRun("study-2", "CASE-2", "run-2");

        AiEvidenceRegion region = service.createProfessionalRegion(
            "CASE-2", "run-2",
            new CreateProfessionalEvidenceRegionRequestDto("axial", "L5-S1", "disc", 3, "ELLIPSE", new EvidenceGeometry.Ellipse(20, 20, 8, 6).toMap(), "Región de interés", null),
            "dr.professional@pfi.local"
        );

        assertEquals("ELLIPSE", region.geometryType());
        assertEquals("professional", region.origin());
    }

    // ---- 3. create AI origin from public API is impossible by construction ----------------

    @Test
    void requestDtoHasNoOriginFieldAtAll() {
        // CreateProfessionalEvidenceRegionRequestDto simply has no `origin` component -- there is
        // no value a caller could set to make createProfessionalRegion produce origin="ai"; the
        // service itself hardcodes "professional" unconditionally (see createsAProfessionalBboxRegion).
        var recordComponents = CreateProfessionalEvidenceRegionRequestDto.class.getRecordComponents();
        assertTrue(java.util.Arrays.stream(recordComponents).noneMatch(c -> c.getName().equals("origin")));
    }

    // ---- 4/5/6/7. correct AI BBOX, second correction wins, original preserved, append-only ----

    @Test
    void correctsAnAiRegionPreservingOriginalAndLatestCorrectionWins() {
        AiProductAnalysisRun run = seedStudyAndRun("study-3", "CASE-3", "run-3");
        seedAiRegion(run.id(), "region-3", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap());

        // Two distinct instants -- a fixed clock would tie both corrections to the exact same
        // createdAt, which is not how two separate real requests ever behave (each gets a later
        // Instant.now()) and would make EffectiveAnnotationResolver's own "latest by createdAt"
        // tie-break arbitrarily on insertion order instead of on time, as a real request never does.
        ProfessionalAnnotationService firstService = new ProfessionalAnnotationService(repository, annotationResolver, Clock.fixed(NOW, ZoneOffset.UTC));
        ProfessionalAnnotationService secondService = new ProfessionalAnnotationService(repository, annotationResolver, Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));

        AiEvidenceRegionCorrection first = firstService.correctRegion(
            "CASE-3", "run-3", "region-3",
            new CorrectEvidenceRegionRequestDto("ELLIPSE", new EvidenceGeometry.Ellipse(20, 20, 8, 6).toMap(), "primera correccion"),
            "dr.professional@pfi.local"
        );
        assertEquals("BBOX", first.originalGeometryType(), "first correction must snapshot the AI's own original geometry");

        AiEvidenceRegionCorrection second = secondService.correctRegion(
            "CASE-3", "run-3", "region-3",
            new CorrectEvidenceRegionRequestDto("POLYGON", new EvidenceGeometry.Polygon(List.of(
                new EvidenceGeometry.Polygon.Point(0, 0), new EvidenceGeometry.Polygon.Point(10, 0), new EvidenceGeometry.Polygon.Point(5, 10)
            )).toMap(), "segunda correccion"),
            "dr.professional@pfi.local"
        );
        assertEquals("ELLIPSE", second.originalGeometryType(), "second correction must snapshot the FIRST correction's geometry, not the AI's");

        // append-only: both corrections remain, AI original untouched.
        assertAll(
            () -> assertEquals(2, repository.findAiEvidenceRegionCorrectionsByAnalysisRunId(run.id()).size()),
            () -> assertEquals("BBOX", repository.findAiEvidenceRegionsByAnalysisRunId(run.id()).get(0).geometryType(), "root AiEvidenceRegion row is never mutated")
        );

        var effective = annotationResolver.effectiveAnnotations(run, true).stream().filter(a -> a.region().id().equals("region-3")).findFirst().orElseThrow();
        assertEquals("POLYGON", effective.effectiveGeometryType(), "the LATEST correction must be effective");
    }

    // ---- 13. invalid geometry ----------------------------------------------------------------

    @Test
    void rejectsInvalidGeometryOnCreate() {
        seedStudyAndRun("study-4", "CASE-4", "run-4");
        assertThrows(IllegalArgumentException.class, () -> service.createProfessionalRegion(
            "CASE-4", "run-4",
            new CreateProfessionalEvidenceRegionRequestDto("sagittal", "L4-L5", "disc", 6, "POLYGON", Map.of("points", List.of(Map.of("x", 0.0, "y", 0.0))), null, null),
            "dr.professional@pfi.local"
        ));
    }

    // ---- 14. out of range ----------------------------------------------------------------

    @Test
    void rejectsOutOfRangeCoordinatesOnCreate() {
        seedStudyAndRun("study-5", "CASE-5", "run-5");
        assertThrows(IllegalArgumentException.class, () -> service.createProfessionalRegion(
            "CASE-5", "run-5",
            new CreateProfessionalEvidenceRegionRequestDto("sagittal", "L4-L5", "disc", 6, "BBOX", new EvidenceGeometry.Bbox(300, 10, 20, 20).toMap(), null, null),
            "dr.professional@pfi.local"
        ));
    }

    // ---- 15. wrong study region ------------------------------------------------------------

    @Test
    void rejectsCorrectionForARegionOfADifferentStudy() {
        AiProductAnalysisRun runA = seedStudyAndRun("study-6a", "CASE-6A", "run-6a");
        seedAiRegion(runA.id(), "region-6a", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap());
        seedStudyAndRun("study-6b", "CASE-6B", "run-6b");

        assertThrows(RunReviewException.class, () -> service.correctRegion(
            "CASE-6B", "run-6b", "region-6a",
            new CorrectEvidenceRegionRequestDto("ELLIPSE", new EvidenceGeometry.Ellipse(20, 20, 8, 6).toMap(), null),
            "dr.professional@pfi.local"
        ));
    }

    @Test
    void rejectsAnAnalysisRunThatDoesNotBelongToTheStudy() {
        seedStudyAndRun("study-7a", "CASE-7A", "run-7a");
        seedStudyAndRun("study-7b", "CASE-7B", "run-7b");
        assertThrows(RunReviewException.class, () -> service.createProfessionalRegion(
            "CASE-7A", "run-7b",
            new CreateProfessionalEvidenceRegionRequestDto("sagittal", "L4-L5", "disc", 6, "BBOX", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap(), null, null),
            "dr.professional@pfi.local"
        ));
    }

    // ---- MASK_REFERENCE is never creatable/correctable here --------------------------------

    @Test
    void rejectsMaskReferenceOnCreateAndCorrect() {
        AiProductAnalysisRun run = seedStudyAndRun("study-8", "CASE-8", "run-8");
        seedAiRegion(run.id(), "region-8", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap());

        Map<String, Object> maskGeometry = new EvidenceGeometry.MaskReference("run-8", "sagittal", "mask.png").toMap();
        assertAll(
            () -> assertThrows(RunReviewException.class, () -> service.createProfessionalRegion(
                "CASE-8", "run-8", new CreateProfessionalEvidenceRegionRequestDto("sagittal", "L4-L5", "disc", 6, "MASK_REFERENCE", maskGeometry, null, null), "dr.professional@pfi.local"
            )),
            () -> assertThrows(RunReviewException.class, () -> service.correctRegion(
                "CASE-8", "run-8", "region-8", new CorrectEvidenceRegionRequestDto("MASK_REFERENCE", maskGeometry, null), "dr.professional@pfi.local"
            ))
        );
    }

    // ---- 22. no reviewer leakage in the response DTOs (checked at the controller/DTO layer,
    // here we just confirm the domain record itself still carries it internally for audit) -----

    @Test
    void authoredByIsStoredInternallyButOnlyThroughTheAuthenticatedIdentityPassedIn() {
        seedStudyAndRun("study-9", "CASE-9", "run-9");
        AiEvidenceRegion region = service.createProfessionalRegion(
            "CASE-9", "run-9",
            new CreateProfessionalEvidenceRegionRequestDto("sagittal", "L4-L5", "disc", 6, "BBOX", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap(), null, null),
            "dr.professional@pfi.local"
        );
        assertEquals("dr.professional@pfi.local", region.authoredBy());
    }

    @Test
    void nullAuthoredByIsAllowedWhenNoAuthenticatedIdentityIsAvailable() {
        seedStudyAndRun("study-10", "CASE-10", "run-10");
        AiEvidenceRegion region = service.createProfessionalRegion(
            "CASE-10", "run-10",
            new CreateProfessionalEvidenceRegionRequestDto("sagittal", "L4-L5", "disc", 6, "BBOX", new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap(), null, null),
            null
        );
        assertNull(region.authoredBy());
    }

    private AiProductAnalysisRun seedStudyAndRun(String studyId, String caseId, String runId) {
        Study study = new Study(studyId, caseId, "registered", "SUBJ-" + caseId, LocalDate.of(2026, 9, 5), "MRI", "fixture", "medium", NOW, NOW);
        repository.saveStudy(study);
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            runId, studyId, caseId, "pfi.ai-product-result.v1", "ext04b5-fixture", "completed",
            true, true, false, "trace-" + runId, "inp_fixture", null, List.of(), Map.of(), NOW, NOW
        );
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        return run;
    }

    private AiEvidenceRegion seedAiRegion(String runId, String regionId, Map<String, Object> geometry) {
        AiEvidenceRegion region = new AiEvidenceRegion(
            regionId, runId, null, null, "sagittal", "L4-L5", "disc", 6,
            "ai", "BBOX", geometry, null,
            "sagittal_spider", "sha256:abc", "pfi.multiplanar-run.v2", null,
            false, null, null, null, NOW
        );
        repository.saveAiEvidenceRegion(region);
        return region;
    }
}
