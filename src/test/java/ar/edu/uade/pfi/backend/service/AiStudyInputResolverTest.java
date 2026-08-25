package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Stage 76-B: input resolution never fabricates an id and never accepts a path fallback. */
class AiStudyInputResolverTest {
    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final AiStudyInputResolver resolver = new AiStudyInputResolver(repository);

    private Study seedStudy(String caseId) {
        Instant now = Instant.now();
        Study study = new Study(UUID.randomUUID().toString(), caseId, "registered", now, now);
        repository.saveStudy(study);
        return study;
    }

    private InputResource seedInput(Study study, String plane, String inputId, boolean analyzable, Instant createdAt) {
        InputResource input = new InputResource(
            UUID.randomUUID().toString(), study.id(), plane, inputId, "dicom", 100L,
            "series", "t2", 20, false, false, analyzable, createdAt
        );
        repository.saveInput(input);
        return input;
    }

    @Test
    void resolvesSagittalOnly() {
        Study study = seedStudy("case-1");
        seedInput(study, "sagittal", "input-sag-1", true, Instant.now());

        AiStudyInputResolver.ResolvedInputs resolved = resolver.resolve("case-1");

        assertEquals("input-sag-1", resolved.sagittalInputId());
        assertNull(resolved.axialInputId());
        assertEquals(study.id(), resolved.studyId());
    }

    @Test
    void resolvesAxialOnly() {
        Study study = seedStudy("case-1");
        seedInput(study, "axial", "input-ax-1", true, Instant.now());

        AiStudyInputResolver.ResolvedInputs resolved = resolver.resolve("case-1");

        assertNull(resolved.sagittalInputId());
        assertEquals("input-ax-1", resolved.axialInputId());
    }

    @Test
    void resolvesBothPlanesWhenBothPresent() {
        Study study = seedStudy("case-1");
        seedInput(study, "sagittal", "input-sag-1", true, Instant.now());
        seedInput(study, "axial", "input-ax-1", true, Instant.now());

        AiStudyInputResolver.ResolvedInputs resolved = resolver.resolve("case-1");

        assertEquals("input-sag-1", resolved.sagittalInputId());
        assertEquals("input-ax-1", resolved.axialInputId());
    }

    @Test
    void throwsStudyNotFoundWhenCaseIdUnknown() {
        assertThrows(StudyNotFoundException.class, () -> resolver.resolve("does-not-exist"));
    }

    @Test
    void throwsUnresolvedWhenNoAnalyzableInputAtAll() {
        seedStudy("case-1");

        assertThrows(AiStudyInputUnresolvedException.class, () -> resolver.resolve("case-1"));
    }

    @Test
    void throwsUnresolvedWhenOnlyNonAnalyzableInputsExist() {
        Study study = seedStudy("case-1");
        seedInput(study, "sagittal", "input-localizer-1", false, Instant.now());

        assertThrows(AiStudyInputUnresolvedException.class, () -> resolver.resolve("case-1"));
    }

    @Test
    void ignoresNonAnalyzableInputsWhenAnAnalyzableOneAlsoExists() {
        Study study = seedStudy("case-1");
        seedInput(study, "sagittal", "input-localizer-1", false, Instant.now());
        seedInput(study, "sagittal", "input-sag-good", true, Instant.now());

        AiStudyInputResolver.ResolvedInputs resolved = resolver.resolve("case-1");

        assertEquals("input-sag-good", resolved.sagittalInputId());
    }

    @Test
    void picksMostRecentAnalyzableInputWhenMultipleExistForSamePlane() {
        Study study = seedStudy("case-1");
        seedInput(study, "sagittal", "input-sag-old", true, Instant.parse("2026-01-01T00:00:00Z"));
        seedInput(study, "sagittal", "input-sag-new", true, Instant.parse("2026-06-01T00:00:00Z"));

        AiStudyInputResolver.ResolvedInputs resolved = resolver.resolve("case-1");

        assertEquals("input-sag-new", resolved.sagittalInputId());
    }

    @Test
    void neverAcceptsAPathAsInputId() {
        Study study = seedStudy("case-1");
        InputResource input = seedInput(study, "sagittal", "input-sag-1", true, Instant.now());

        AiStudyInputResolver.ResolvedInputs resolved = resolver.resolve("case-1");

        assertEquals(input.inputId(), resolved.sagittalInputId());
    }
}
