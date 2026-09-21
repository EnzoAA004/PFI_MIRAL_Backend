package ar.edu.uade.pfi.backend.service;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;

import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@code sagittalInputId}/{@code axialInputId} a product-analysis run needs from the
 * Study's own registered inputs (stage 76-B) — never from a Frontend-supplied path and never
 * fabricated. The Frontend only needs to know {@code caseId}; everything else is resolved here.
 */
@Component
public class AiStudyInputResolver {
    private final StudyRepository repository;

    public AiStudyInputResolver(StudyRepository repository) {
        this.repository = repository;
    }

    public record ResolvedInputs(String studyId, String sagittalInputId, String axialInputId) {
    }

    public ResolvedInputs resolve(String caseId) {
        Study study = repository.findStudyByCaseId(caseId).orElseThrow(() -> new StudyNotFoundException(caseId));
        List<InputResource> inputs = repository.findInputsByStudyId(study.id());
        String sagittalInputId = latestAnalyzableInputId(inputs, "sagittal");
        String axialInputId = latestAnalyzableInputId(inputs, "axial");
        if (sagittalInputId == null && axialInputId == null) {
            throw new AiStudyInputUnresolvedException(caseId);
        }
        return new ResolvedInputs(study.id(), sagittalInputId, axialInputId);
    }

    private String latestAnalyzableInputId(List<InputResource> inputs, String plane) {
        Optional<InputResource> resolved = inputs.stream()
            .filter(InputResource::analyzable)
            .filter(input -> plane.equals(input.plane()))
            .max(Comparator.comparing(InputResource::createdAt));
        return resolved.map(InputResource::inputId).orElse(null);
    }
}
