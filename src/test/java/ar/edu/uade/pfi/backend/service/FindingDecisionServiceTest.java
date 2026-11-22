package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.FindingDecision;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.service.exception.RunReviewException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Decisiones del profesional sobre hallazgos: se guardan por corrida, se reemplazan, se quitan y se validan. */
class FindingDecisionServiceTest {
    private final AssistantTestFixtures fx = new AssistantTestFixtures();

    private FindingDecisionService service() {
        return new FindingDecisionService(fx.repository, null);
    }

    private Study studyWithRun(String caseId, String runId) {
        Study study = fx.study(caseId, "P-" + caseId, "S-" + caseId, null);
        fx.run(study, runId, Instant.parse("2026-11-21T10:00:00Z"), List.of(), List.of());
        return study;
    }

    @Test
    void startsEmptyAndStoresAcceptedRejectedAndDoubtfulPerFinding() {
        Study study = studyWithRun("C-1", "run-1");
        FindingDecisionService service = service();
        assertTrue(service.list("C-1", "run-1").isEmpty(), "sin decisiones = sin revisar");

        service.decide("C-1", "run-1", "L4-L5|schmorl", "aceptado", "", "marta", "t1");
        service.decide("C-1", "run-1", "L5-S1|pfirrmann", "rechazado", "no coincide con T2", "marta", "t2");
        List<FindingDecision> all = service.decide("C-1", "run-1", "L1-L2|modic", "DUDOSO", " revisar ", "marta", "t3");

        assertEquals(3, all.size());
        assertEquals("dudoso", all.stream().filter(d -> d.findingKey().equals("L1-L2|modic")).findFirst().orElseThrow().decision());
        assertEquals("revisar", all.stream().filter(d -> d.findingKey().equals("L1-L2|modic")).findFirst().orElseThrow().note());
        assertEquals(3, fx.repository.findFindingDecisions(study.id(), "run-1").size());
    }

    @Test
    void aNewDecisionReplacesTheOldOneAndEmptyClearsIt() {
        studyWithRun("C-1", "run-1");
        FindingDecisionService service = service();
        service.decide("C-1", "run-1", "k", "aceptado", "", "marta", "t");
        List<FindingDecision> replaced = service.decide("C-1", "run-1", "k", "rechazado", "otra opinion", "marta", "t");
        assertEquals(1, replaced.size());
        assertEquals("rechazado", replaced.get(0).decision());
        assertTrue(service.decide("C-1", "run-1", "k", null, null, "marta", "t").isEmpty());
    }

    @Test
    void decisionsBelongToTheExactRun() {
        Study study = studyWithRun("C-1", "run-1");
        fx.run(study, "run-2", Instant.parse("2026-11-21T11:00:00Z"), List.of(), List.of());
        FindingDecisionService service = service();
        service.decide("C-1", "run-1", "k", "aceptado", "", "marta", "t");
        assertEquals(1, service.list("C-1", "run-1").size());
        assertTrue(service.list("C-1", "run-2").isEmpty());
    }

    @Test
    void invalidInputIsRejectedNeverCoerced() {
        studyWithRun("C-1", "run-1");
        studyWithRun("C-2", "run-2");
        FindingDecisionService service = service();
        assertThrows(RunReviewException.class, () -> service.decide("C-1", "run-1", "k", "quizas", "", "m", "t"));
        assertThrows(RunReviewException.class, () -> service.decide("C-1", "run-1", " ", "aceptado", "", "m", "t"));
        assertThrows(RunReviewException.class, () -> service.decide("C-1", "run-1", "k", "aceptado", "x".repeat(2001), "m", "t"));
        assertThrows(RunReviewException.class, () -> service.list("C-1", null));
        assertThrows(RunReviewException.class, () -> service.list("C-1", "run-2"), "la corrida debe ser del estudio");
    }
}
