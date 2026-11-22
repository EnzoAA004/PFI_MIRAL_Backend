package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.FindingDecision;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import ar.edu.uade.pfi.backend.service.exception.RunReviewException;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Decisiones del profesional (aceptado / rechazado / dudoso) sobre los hallazgos de UNA corrida. Es trabajo propio del
 * profesional: no deriva, completa ni reinterpreta nada, y no toca predicciones, correcciones ni el estado de revision.
 */
@Service
public class FindingDecisionService {
    static final Set<String> DECISIONS = Set.of("aceptado", "rechazado", "dudoso");
    static final int MAX_KEY = 200;
    static final int MAX_NOTE = 2000;

    private final StudyRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    public FindingDecisionService(StudyRepository repository, @Nullable AuditService auditService) {
        this(repository, auditService, Clock.systemUTC());
    }

    FindingDecisionService(StudyRepository repository, @Nullable AuditService auditService, Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
    }

    public List<FindingDecision> list(String caseId, String runId) {
        Resolved resolved = resolve(caseId, runId);
        return repository.findFindingDecisions(resolved.study.id(), resolved.run.multiplanarRunId());
    }

    /** {@code decision == null} (o vacio) quita la decision y el hallazgo vuelve a "sin revisar". */
    public List<FindingDecision> decide(String caseId, String runId, String findingKey, String decision, String note, String actor, String traceId) {
        Resolved resolved = resolve(caseId, runId);
        String key = findingKey == null ? "" : findingKey.trim();
        if (key.isEmpty() || key.length() > MAX_KEY) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "FINDING_KEY_INVALID", "Falta el identificador del hallazgo o es demasiado largo.");
        }
        String studyId = resolved.study.id();
        String run = resolved.run.multiplanarRunId();
        if (decision == null || decision.isBlank()) {
            repository.deleteFindingDecision(studyId, run, key);
            audit("finding.decision_cleared", studyId, traceId, actor, Map.of("findingKey", key));
        } else {
            String value = decision.trim().toLowerCase(Locale.ROOT);
            if (!DECISIONS.contains(value)) {
                throw new RunReviewException(HttpStatus.BAD_REQUEST, "FINDING_DECISION_INVALID", "La decision debe ser aceptado, rechazado o dudoso.");
            }
            String cleanNote = note == null ? "" : note.trim();
            if (cleanNote.length() > MAX_NOTE) {
                throw new RunReviewException(HttpStatus.BAD_REQUEST, "FINDING_NOTE_TOO_LONG", "La nota supera los " + MAX_NOTE + " caracteres.");
            }
            repository.saveFindingDecision(new FindingDecision(studyId, run, key, value, cleanNote, actor == null ? "" : actor, clock.instant()));
            audit("finding.decided", studyId, traceId, actor, Map.of("findingKey", key, "decision", value));
        }
        return repository.findFindingDecisions(studyId, run);
    }

    private Resolved resolve(String caseId, String runId) {
        Study study = repository.findStudyByCaseId(caseId).orElseThrow(() -> new StudyNotFoundException(caseId));
        if (runId == null || runId.isBlank()) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "FINDING_RUN_REQUIRED", "Hace falta indicar la corrida del analisis.");
        }
        StudyRun run = repository.findRunByMultiplanarRunId(runId.trim()).filter(candidate -> candidate.studyId().equals(study.id()))
            .orElseThrow(() -> new RunReviewException(HttpStatus.NOT_FOUND, "FINDING_RUN_NOT_FOUND", "No se encontro el analisis indicado para este estudio."));
        return new Resolved(study, run);
    }

    private void audit(String action, String studyId, String traceId, String actor, Map<String, Object> metadata) {
        if (auditService == null) return;
        try {
            auditService.record(actor == null || actor.isBlank() ? "system" : actor, action, studyId, traceId == null || traceId.isBlank() ? "unavailable" : traceId, new LinkedHashMap<>(metadata));
        } catch (RuntimeException ignored) {
            // La decision no debe fallar porque no se pueda registrar la auditoria.
        }
    }

    private record Resolved(Study study, StudyRun run) {}
}
