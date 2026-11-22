package ar.edu.uade.pfi.backend.service;
import ar.edu.uade.pfi.backend.service.exception.RunReviewException;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;

import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.LongitudinalComparisonResultDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Etapa 79: comparación determinística y a demanda (nunca persistida — 79-J) de las mediciones de la IA
 * entre dos Studies del mismo paciente. Sin LLM, sin ML, sin heurísticas clínicas — solo
 * aritmética {@code current - baseline} sobre valores ya persistidos y, si corresponde, ya corregidos,
 * y este servicio nunca emite un juicio clínico (79-G).
 */
@Service
public class LongitudinalComparisonService {
    /** Solo igualdad de punto flotante — nunca un "umbral de significancia clínica" (79-H). */
    private static final double EPSILON = 1e-9;
    /** Ordenamiento determinístico de las filas de comparación — la misma precedencia de campos que la vieja clave de string concatenado. */
    private static final java.util.Comparator<EffectiveMeasurementResolver.MatchKey> MATCH_KEY_COMPARATOR =
        java.util.Comparator.comparing(EffectiveMeasurementResolver.MatchKey::level)
            .thenComparing(EffectiveMeasurementResolver.MatchKey::plane)
            .thenComparing(EffectiveMeasurementResolver.MatchKey::structure)
            .thenComparing(EffectiveMeasurementResolver.MatchKey::metric);

    private final StudyRepository repository;
    private final EffectiveAnalysisRunSelector runSelector;
    private final EffectiveMeasurementResolver measurementResolver;
    private final Clock clock;

    @Autowired
    public LongitudinalComparisonService(StudyRepository repository, EffectiveAnalysisRunSelector runSelector, EffectiveMeasurementResolver measurementResolver) {
        this(repository, runSelector, measurementResolver, Clock.systemUTC());
    }

    LongitudinalComparisonService(StudyRepository repository, EffectiveAnalysisRunSelector runSelector, EffectiveMeasurementResolver measurementResolver, Clock clock) {
        this.repository = repository;
        this.runSelector = runSelector;
        this.measurementResolver = measurementResolver;
        this.clock = clock;
    }

    public LongitudinalComparisonResultDto compare(String currentCaseId, String baselineCaseId) {
        Study current = repository.findStudyByCaseId(currentCaseId).orElseThrow(() -> new StudyNotFoundException(currentCaseId));
        Study baseline = repository.findStudyByCaseId(baselineCaseId).orElseThrow(() -> new StudyNotFoundException(baselineCaseId));

        if (current.id().equals(baseline.id())) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "LONGITUDINAL_SAME_STUDY", "No se puede comparar un estudio consigo mismo.");
        }

        requireSamePatient(current, baseline);

        AiProductAnalysisRun currentRun = runSelector.selectEffectiveRun(current.id())
            .orElseThrow(() -> new RunReviewException(HttpStatus.UNPROCESSABLE_ENTITY, "LONGITUDINAL_NO_USABLE_ANALYSIS_CURRENT", "El estudio actual no tiene un analisis utilizable."));
        AiProductAnalysisRun baselineRun = runSelector.selectEffectiveRun(baseline.id())
            .orElseThrow(() -> new RunReviewException(HttpStatus.UNPROCESSABLE_ENTITY, "LONGITUDINAL_NO_USABLE_ANALYSIS_BASELINE", "El estudio de referencia no tiene un analisis utilizable."));

        List<EffectiveMeasurementResolver.EffectiveMeasurement> baselineMeasurements = measurementResolver.effectiveMeasurements(baselineRun);
        List<EffectiveMeasurementResolver.EffectiveMeasurement> currentMeasurements = measurementResolver.effectiveMeasurements(currentRun);
        List<LongitudinalComparisonResultDto.MeasurementComparison> comparisons = compareMeasurements(baselineMeasurements, currentMeasurements);

        return new LongitudinalComparisonResultDto(
            baseline.caseId(),
            current.caseId(),
            baseline.id(),
            current.id(),
            baselineRun.id(),
            currentRun.id(),
            baseline.studyDate(),
            current.studyDate(),
            clock.instant(),
            comparisons
        );
    }

    /**
     * Etapa 79-D: fail closed. Dos Studies son "el mismo paciente" solo cuando ambos llevan un
     * {@code subjectRef} no vacío y esos valores coinciden sin distinguir mayúsculas y recortados — la misma
     * regla que ya usan {@code StudyRepository.findStudiesBySubjectRef}/{@code PatientHistoryService}
     * (reconocimiento de la etapa 79-A: no existe ninguna entidad {@code Patient} en este dominio; la
     * igualdad de subjectRef es la única evidencia segura). Un Study sin subjectRef nunca puede probarse
     * como del mismo paciente que nada — incluido otro Study que tampoco tenga uno.
     *
     * <p>Lanza {@link StudyNotFoundException} para el lado baseline en lugar de un error distinto de
     * "forbidden", siguiendo la misma convención IDOR ya usada para las búsquedas de análisis entre Studies
     * (etapa 76/78: un recurso que pertenece a otro Study devuelve 404, sin confirmar nunca que
     * existe) — un Study baseline que existe pero pertenece a otro paciente nunca debe poder
     * distinguirse de uno que no existe en absoluto.
     */
    private void requireSamePatient(Study current, Study baseline) {
        String currentSubject = normalizedSubjectRef(current.subjectRef());
        String baselineSubject = normalizedSubjectRef(baseline.subjectRef());
        if (currentSubject == null || baselineSubject == null || !currentSubject.equals(baselineSubject)) {
            throw new StudyNotFoundException(baseline.caseId());
        }
    }

    private String normalizedSubjectRef(String subjectRef) {
        return subjectRef == null || subjectRef.isBlank() ? null : subjectRef.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Etapa 79-E: empareja por la clave exacta {@code (level, plane, structure, metric)} —
     * {@code areaMm2} nunca se compara contra {@code majorAxisMm}, {@code mm} nunca se compara
     * contra {@code mm²}. Una medición con {@code level=null} NUNCA se empareja con nada
     * (incluida otra entrada con {@code level=null}) — siempre se renderiza como su propia
     * fila {@code level_unresolved} (etapa 79-E "level null").
     */
    private List<LongitudinalComparisonResultDto.MeasurementComparison> compareMeasurements(
        List<EffectiveMeasurementResolver.EffectiveMeasurement> baseline,
        List<EffectiveMeasurementResolver.EffectiveMeasurement> current
    ) {
        List<LongitudinalComparisonResultDto.MeasurementComparison> results = new ArrayList<>();

        for (EffectiveMeasurementResolver.EffectiveMeasurement measurement : baseline) {
            if (measurement.level() == null) results.add(unresolvedRow(measurement, true));
        }
        for (EffectiveMeasurementResolver.EffectiveMeasurement measurement : current) {
            if (measurement.level() == null) results.add(unresolvedRow(measurement, false));
        }

        Map<EffectiveMeasurementResolver.MatchKey, EffectiveMeasurementResolver.EffectiveMeasurement> baselineByKey = indexByKey(baseline);
        Map<EffectiveMeasurementResolver.MatchKey, EffectiveMeasurementResolver.EffectiveMeasurement> currentByKey = indexByKey(current);
        Set<EffectiveMeasurementResolver.MatchKey> keys = new TreeSet<>(MATCH_KEY_COMPARATOR);
        keys.addAll(baselineByKey.keySet());
        keys.addAll(currentByKey.keySet());
        for (EffectiveMeasurementResolver.MatchKey key : keys) {
            results.add(compareOne(baselineByKey.get(key), currentByKey.get(key)));
        }
        return results;
    }

    private Map<EffectiveMeasurementResolver.MatchKey, EffectiveMeasurementResolver.EffectiveMeasurement> indexByKey(List<EffectiveMeasurementResolver.EffectiveMeasurement> measurements) {
        Map<EffectiveMeasurementResolver.MatchKey, EffectiveMeasurementResolver.EffectiveMeasurement> index = new LinkedHashMap<>();
        for (EffectiveMeasurementResolver.EffectiveMeasurement measurement : measurements) {
            if (measurement.level() == null) continue;
            index.put(measurementResolver.matchKey(measurement), measurement);
        }
        return index;
    }

    private LongitudinalComparisonResultDto.MeasurementComparison unresolvedRow(EffectiveMeasurementResolver.EffectiveMeasurement measurement, boolean isBaseline) {
        return new LongitudinalComparisonResultDto.MeasurementComparison(
            null,
            measurement.plane(),
            measurement.structure(),
            measurement.metric(),
            measurement.unit(),
            isBaseline ? toEffectiveValueDto(measurement) : null,
            isBaseline ? null : toEffectiveValueDto(measurement),
            null,
            null,
            null,
            "level_unresolved"
        );
    }

    private LongitudinalComparisonResultDto.MeasurementComparison compareOne(EffectiveMeasurementResolver.EffectiveMeasurement baseline, EffectiveMeasurementResolver.EffectiveMeasurement current) {
        if (baseline == null) {
            return new LongitudinalComparisonResultDto.MeasurementComparison(
                current.level(), current.plane(), current.structure(), current.metric(), current.unit(),
                null, toEffectiveValueDto(current), null, null, null, "missing_baseline"
            );
        }
        if (current == null) {
            return new LongitudinalComparisonResultDto.MeasurementComparison(
                baseline.level(), baseline.plane(), baseline.structure(), baseline.metric(), baseline.unit(),
                toEffectiveValueDto(baseline), null, null, null, null, "missing_current"
            );
        }
        // Defensivo: hoy inalcanzable (la unidad es fija por métrica del lado del servidor), se mantiene por completitud de la etapa 79-Q.
        if (!baseline.unit().equals(current.unit())) {
            return new LongitudinalComparisonResultDto.MeasurementComparison(
                baseline.level(), baseline.plane(), baseline.structure(), baseline.metric(), baseline.unit(),
                toEffectiveValueDto(baseline), toEffectiveValueDto(current), null, null, null, "unit_mismatch"
            );
        }
        double deltaAbsolute = current.effectiveValue() - baseline.effectiveValue();
        Double deltaPercent = baseline.effectiveValue() == 0
            ? null
            : (deltaAbsolute / Math.abs(baseline.effectiveValue())) * 100;
        return new LongitudinalComparisonResultDto.MeasurementComparison(
            baseline.level(), baseline.plane(), baseline.structure(), baseline.metric(), baseline.unit(),
            toEffectiveValueDto(baseline), toEffectiveValueDto(current),
            deltaAbsolute, deltaPercent, trendFor(deltaAbsolute), "comparable"
        );
    }

    private String trendFor(double deltaAbsolute) {
        if (Math.abs(deltaAbsolute) < EPSILON) return "unchanged";
        return deltaAbsolute > 0 ? "increased" : "decreased";
    }

    private LongitudinalComparisonResultDto.EffectiveValue toEffectiveValueDto(EffectiveMeasurementResolver.EffectiveMeasurement measurement) {
        return new LongitudinalComparisonResultDto.EffectiveValue(
            measurement.automaticValue(), measurement.correctedValue(), measurement.effectiveValue(), measurement.source()
        );
    }
}
