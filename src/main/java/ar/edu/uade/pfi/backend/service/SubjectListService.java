package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRunReview;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.SubjectSummaryDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Etapa 85: la "lista de pacientes" que necesita el Frontend es una proyección/agregado sobre
 * {@code Study.subjectRef} — nunca una entidad {@code Patient} persistida (la misma decisión de
 * arquitectura cerrada que en la Etapa 84). Esta es la agrupación del lado del Backend que {@code /pacientes} necesita en lugar
 * del nunca implementado {@code GET /api/patients} que llamaba la vieja y ya retirada {@code PatientDetailView}
 * (hallazgo de la Etapa 84).
 *
 * <p>85-S: implementado como una única consulta {@code findAllStudies()} seguida de agrupación en memoria,
 * no N+1 consultas por sujeto — decisión deliberada y documentada: el dataset realista de este MVP
 * (un puñado de Studies entre un puñado de sujetos) hace que un único escaneo completo sea más barato y
 * simple que una consulta paginada/con joins, y {@code StudyRepository} ya carga Studies de esta
 * forma en otros lugares (p. ej. el worklist legacy). Solo la búsqueda del *análisis efectivo* del
 * último Study de cada sujeto es una consulta por sujeto (vía {@link EffectiveAnalysisRunSelector}
 * + una búsqueda de revisión) — acotada por la cantidad de *sujetos distintos*, no por la cantidad de Studies.
 */
@Service
public class SubjectListService {
    private final StudyRepository repository;
    private final EffectiveAnalysisRunSelector runSelector;

    public SubjectListService(StudyRepository repository, EffectiveAnalysisRunSelector runSelector) {
        this.repository = repository;
        this.runSelector = runSelector;
    }

    /**
     * Etapa 85-D: fail closed. Un {@code subjectRef} {@code null}/en blanco nunca forma una fila de "paciente"
     * — se excluye por completo, nunca se agrupa en un "paciente desconocido" placeholder que
     * fusionaría en silencio Studies no relacionados.
     *
     * <p>Etapa 85-C: la clave de agrupación está recortada + en minúsculas (la misma regla sin distinguir mayúsculas que la Etapa 84
     * ya usa vía el SQL de {@code findStudiesBySubjectRef}) — pero el {@code subjectRef} *mostrado*
     * es el valor crudo del propio último Study (sin pasar a minúsculas), de modo que la navegación de vuelta a
     * {@code GET /api/subjects/{subjectRef}/history} siempre use una capitalización real y existente.
     */
    public List<SubjectSummaryDto> list() {
        return list(null);
    }

    public List<SubjectSummaryDto> list(List<String> accessibleCaseIds) {
        java.util.Set<String> allowed = accessibleCaseIds == null ? null : java.util.Set.copyOf(accessibleCaseIds);
        Map<String, List<Study>> bySubject = new LinkedHashMap<>();
        for (Study study : repository.findAllStudies()) {
            if (allowed != null && !allowed.contains(study.caseId())) continue;
            String key = normalizeKey(study.subjectRef());
            if (key == null) continue;
            bySubject.computeIfAbsent(key, ignored -> new ArrayList<>()).add(study);
        }

        List<SubjectSummaryDto> result = new ArrayList<>();
        for (List<Study> studiesInSubject : bySubject.values()) {
            result.add(toSummary(studiesInSubject));
        }
        result.sort(latestStudyDateDescendingThenSubjectRef());
        return result;
    }

    private SubjectSummaryDto toSummary(List<Study> studies) {
        Study latest = studies.stream().max(studyDateAscendingThenCaseId()).orElseThrow();
        AiProductAnalysisRun effectiveRun = runSelector.selectEffectiveRun(latest.id()).orElse(null);
        String latestReviewStatus = effectiveRun == null ? null
            : repository.findAiProductAnalysisRunReview(effectiveRun.id())
                .map(AiProductAnalysisRunReview::reviewStatus)
                .orElse("pending");
        return new SubjectSummaryDto(
            latest.subjectRef().trim(),
            studies.size(),
            latest.studyDate(),
            latest.caseId(),
            effectiveRun == null ? null : effectiveRun.status(),
            latestReviewStatus
        );
    }

    /** Etapa 85-F: la misma regla de "último Study" que la Etapa 84 -- gana studyDate, una fecha null nunca es "la última" frente a una real, desempate determinístico por caseId. */
    private Comparator<Study> studyDateAscendingThenCaseId() {
        return Comparator
            .comparing(Study::studyDate, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Study::caseId);
    }

    /** Etapa 85-K: primero los sujetos con actividad más reciente; un latestStudyDate null va al final, desempate determinístico por subjectRef. */
    private Comparator<SubjectSummaryDto> latestStudyDateDescendingThenSubjectRef() {
        return Comparator
            .comparing(SubjectSummaryDto::latestStudyDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(SubjectSummaryDto::subjectRef);
    }

    private String normalizeKey(String subjectRef) {
        return subjectRef == null || subjectRef.isBlank() ? null : subjectRef.trim().toLowerCase(Locale.ROOT);
    }
}
