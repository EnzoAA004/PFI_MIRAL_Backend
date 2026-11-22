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
 * Resuelve el {@code sagittalInputId}/{@code axialInputId} que necesita una corrida de product-analysis a partir de los
 * inputs registrados del propio Study (etapa 76-B) — nunca de un path provisto por el Frontend y nunca
 * fabricado. El Frontend solo necesita conocer {@code caseId}; todo lo demás se resuelve acá.
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
