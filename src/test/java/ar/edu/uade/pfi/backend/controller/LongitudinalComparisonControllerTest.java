package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.LongitudinalComparisonResultDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.EffectiveAnalysisRunSelector;
import ar.edu.uade.pfi.backend.service.EffectiveMeasurementResolver;
import ar.edu.uade.pfi.backend.service.LongitudinalComparisonService;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Stage 79: the controller stays thin — this only confirms wiring, the logic itself is covered by LongitudinalComparisonServiceTest. */
class LongitudinalComparisonControllerTest {
    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final LongitudinalComparisonController controller = new LongitudinalComparisonController(
        new LongitudinalComparisonService(repository, new EffectiveAnalysisRunSelector(repository), new EffectiveMeasurementResolver(repository))
    );

    private Study seedStudy(String caseId, String subjectRef) {
        Instant now = Instant.now();
        Study study = new Study(UUID.randomUUID().toString(), caseId, "registered", subjectRef, LocalDate.of(2026, 1, 1), "MRI", "", "medium", now, now);
        repository.saveStudy(study);
        return study;
    }

    private void seedRunWithMeasurement(Study study, double areaMm2) {
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            runId, study.id(), study.caseId(), "pfi.ai-product-result.v1", "post-e50-ai-product-freeze-v1",
            "completed", true, true, false, "trace-1", "sag-1", "ax-1", List.of(), null, now, now
        );
        AiDiscLevelResult level = new AiDiscLevelResult(UUID.randomUUID().toString(), runId, 0, "L4-L5", "localized", "67B2", true, false, "paired", true, false, false, null, false);
        AiMeasurementResult measurement = new AiMeasurementResult(UUID.randomUUID().toString(), runId, level.id(), "sagittal", "L4-L5", "disc", areaMm2, null, null, true);
        repository.saveAiProductAnalysisRun(run, List.of(level), List.of(measurement));
    }

    @Test
    void comparesTwoStudiesOfTheSamePatient() {
        Study baseline = seedStudy("case-baseline", "PAT-1");
        Study current = seedStudy("case-current", "PAT-1");
        seedRunWithMeasurement(baseline, 12.0);
        seedRunWithMeasurement(current, 13.0);

        LongitudinalComparisonResultDto result = controller.compare("case-current", "case-baseline");
        assertEquals("case-baseline", result.baselineCaseId());
        assertEquals(1.0, result.measurements().get(0).deltaAbsolute());
    }

    @Test
    void rejectsCrossPatientComparisonAtControllerLayer() {
        Study baseline = seedStudy("case-baseline", "PAT-1");
        Study current = seedStudy("case-current", "PAT-2");
        seedRunWithMeasurement(baseline, 12.0);
        seedRunWithMeasurement(current, 13.0);

        assertThrows(StudyNotFoundException.class, () -> controller.compare("case-current", "case-baseline"));
    }
}
