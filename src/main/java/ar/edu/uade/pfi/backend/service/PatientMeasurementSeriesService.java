package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.MeasurementSeriesDto;
import ar.edu.uade.pfi.backend.dto.MeasurementSeriesDto.MeasurementSeriesKeyDto;
import ar.edu.uade.pfi.backend.dto.MeasurementSeriesDto.Point;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Etapa 84-I: un valor de medición determinístico a lo largo de N Studies del mismo paciente
 * ({@code subjectRef}) — nunca un nuevo cómputo de IA/ML, nunca persistido (84 "NO persistir series
 * calculadas", replicando la propia decisión de "nunca persistido" de la Etapa 79-J para la comparación
 * de dos Studies). Reutiliza textualmente {@link EffectiveAnalysisRunSelector} (qué corrida representa a un Study) y
 * {@link EffectiveMeasurementResolver} (valor efectivo + clave de emparejamiento) — esta clase
 * solo agrega la iteración "a lo largo de N Studies, no solo dos" por encima de ambos.
 */
@Service
public class PatientMeasurementSeriesService {
    private final StudyRepository repository;
    private final EffectiveAnalysisRunSelector runSelector;
    private final EffectiveMeasurementResolver measurementResolver;

    public PatientMeasurementSeriesService(
        StudyRepository repository,
        EffectiveAnalysisRunSelector runSelector,
        EffectiveMeasurementResolver measurementResolver
    ) {
        this.repository = repository;
        this.runSelector = runSelector;
        this.measurementResolver = measurementResolver;
    }

    /**
     * Etapa 84-M: solo se devuelven las claves con al menos 2 puntos comparables — una "serie" de un solo
     * Study no es una tendencia, y devolverla solo sumaría ruido de UI (el Frontend ya puede
     * mostrar directamente las mediciones propias de un único Study, sin necesidad de serie).
     */
    public List<MeasurementSeriesKeyDto> availableSeriesKeys(String subjectRef) {
        return availableSeriesKeys(subjectRef, null);
    }

    public List<MeasurementSeriesKeyDto> availableSeriesKeys(String subjectRef, List<String> accessibleCaseIds) {
        String normalized = SubjectReferenceValidator.normalize(subjectRef);
        Map<EffectiveMeasurementResolver.MatchKey, Long> countByKey = new LinkedHashMap<>();
        Map<EffectiveMeasurementResolver.MatchKey, String> unitByKey = new LinkedHashMap<>();
        for (Study study : accessibleStudies(normalized, accessibleCaseIds)) {
            for (EffectiveMeasurementResolver.EffectiveMeasurement measurement : effectiveMeasurementsFor(study)) {
                if (measurement.level() == null) continue; // 84-K: un nivel null nunca se une a una serie.
                EffectiveMeasurementResolver.MatchKey key = measurementResolver.matchKey(measurement);
                countByKey.merge(key, 1L, Long::sum);
                unitByKey.putIfAbsent(key, measurement.unit());
            }
        }
        return countByKey.entrySet().stream()
            .filter(entry -> entry.getValue() >= 2)
            .map(entry -> toKeyDto(entry.getKey(), unitByKey.get(entry.getKey())))
            .toList();
    }

    /**
     * Etapa 84-F: la serie en sí es cronológica ascendente (la más vieja primero) — el orden opuesto
     * al del endpoint de lista de estudios (studyDate DESC), siguiendo la distinción explícita del brief de la etapa
     * ("Para la lista: studyDate DESC. Para timeline visual: ASC cronológico").
     */
    public MeasurementSeriesDto series(String subjectRef, String level, String plane, String structure, String metric) {
        return series(subjectRef, level, plane, structure, metric, null);
    }

    public MeasurementSeriesDto series(String subjectRef, String level, String plane, String structure, String metric, List<String> accessibleCaseIds) {
        String normalized = SubjectReferenceValidator.normalize(subjectRef);
        EffectiveMeasurementResolver.MatchKey targetKey = new EffectiveMeasurementResolver.MatchKey(level, plane, structure, metric);

        List<Study> studies = new ArrayList<>(accessibleStudies(normalized, accessibleCaseIds));
        studies.sort(studyDateAscendingThenCaseId());

        List<Point> points = new ArrayList<>();
        String unit = null;
        for (Study study : studies) {
            for (EffectiveMeasurementResolver.EffectiveMeasurement measurement : effectiveMeasurementsFor(study)) {
                if (measurement.level() == null) continue;
                if (!measurementResolver.matchKey(measurement).equals(targetKey)) continue;
                unit = measurement.unit();
                points.add(new Point(
                    study.caseId(), study.studyDate(),
                    measurement.automaticValue(), measurement.correctedValue(), measurement.effectiveValue(), measurement.source()
                ));
                break; // un punto por Study para esta clave -- un Study nunca aporta dos puntos a la misma serie.
            }
        }
        return new MeasurementSeriesDto(new MeasurementSeriesKeyDto(level, plane, structure, metric, unit), points);
    }

    private List<Study> accessibleStudies(String subjectRef, List<String> accessibleCaseIds) {
        List<Study> studies = repository.findStudiesBySubjectRef(subjectRef);
        if (accessibleCaseIds == null) return studies;
        java.util.Set<String> allowed = java.util.Set.copyOf(accessibleCaseIds);
        return studies.stream().filter(study -> allowed.contains(study.caseId())).toList();
    }

    private List<EffectiveMeasurementResolver.EffectiveMeasurement> effectiveMeasurementsFor(Study study) {
        return runSelector.selectEffectiveRun(study.id())
            .map(this::safeEffectiveMeasurements)
            .orElse(List.of());
    }

    private List<EffectiveMeasurementResolver.EffectiveMeasurement> safeEffectiveMeasurements(AiProductAnalysisRun run) {
        return measurementResolver.effectiveMeasurements(run);
    }

    /** 84-F: desempate determinístico cuando dos Studies comparten (o ambos carecen de) un studyDate. */
    private Comparator<Study> studyDateAscendingThenCaseId() {
        return Comparator
            .comparing(Study::studyDate, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Study::caseId);
    }

    private MeasurementSeriesKeyDto toKeyDto(EffectiveMeasurementResolver.MatchKey key, String unit) {
        return new MeasurementSeriesKeyDto(key.level(), key.plane(), key.structure(), key.metric(), unit);
    }
}
