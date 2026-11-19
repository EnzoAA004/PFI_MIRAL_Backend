package ar.edu.uade.pfi.backend.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.Study;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryAiPipelineRunScopeTest {
    @Test
    void bindsPipelineRunToExistingCaseWithoutAllowingReassignment() {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        Instant now = Instant.now();
        repository.saveStudy(new Study("study-a", "case-a", "ready", now, now));
        repository.saveStudy(new Study("study-b", "case-b", "ready", now, now));

        assertFalse(repository.registerAiPipelineRunResource("run-missing", "case-missing"));
        assertTrue(repository.registerAiPipelineRunResource("run-a", "case-a"));
        assertEquals("case-a", repository.resolveStudyCaseId("run-a").orElseThrow());
        assertTrue(repository.registerAiPipelineRunResource("run-a", "case-a"));
        assertThrows(IllegalStateException.class, () -> repository.registerAiPipelineRunResource("run-a", "case-b"));
        assertEquals("case-a", repository.resolveStudyCaseId("run-a").orElseThrow());
        assertTrue(repository.resolveStudyCaseId("unknown-historical-run").isEmpty());
    }
}
