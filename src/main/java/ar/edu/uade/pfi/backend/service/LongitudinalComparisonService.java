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
 * Stage 79: deterministic, on-demand (never persisted — 79-J) comparison of AI measurements
 * between two Studies of the same patient. No LLM, no ML, no clinical heuristics — only
 * {@code current - baseline} arithmetic over already-persisted, already-corrected-if-applicable
 * values, and this service never emits a clinical judgment (79-G).
 */
@Service
public class LongitudinalComparisonService {
    /** Floating-point equality only — never a "clinical significance threshold" (79-H). */
    private static final double EPSILON = 1e-9;
    /** Deterministic ordering of comparison rows — same field precedence as the old concatenated-string key. */
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
     * Stage 79-D: fail closed. Two Studies are only "the same patient" when both carry a
     * non-blank {@code subjectRef} and those values match case-insensitively/trimmed — the exact
     * rule {@code StudyRepository.findStudiesBySubjectRef}/{@code PatientHistoryService} already
     * use (stage 79-A recon: no {@code Patient} entity exists anywhere in this domain; subjectRef
     * equality is the only safe evidence). A Study with no subjectRef can never be proven
     * same-patient as anything — including another Study also missing one.
     *
     * <p>Throws {@link StudyNotFoundException} for the baseline side rather than a distinct
     * "forbidden" error, following the same IDOR convention already used for cross-Study analysis
     * lookups (stage 76/78: a resource belonging to a different Study 404s, never confirming it
     * exists) — a baseline Study that exists but belongs to a different patient must never be
     * distinguishable from one that doesn't exist at all.
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
     * Stage 79-E: matches by the exact {@code (level, plane, structure, metric)} key —
     * {@code areaMm2} is never compared against {@code majorAxisMm}, {@code mm} is never compared
     * against {@code mm²}. A {@code level=null} measurement is NEVER paired with anything
     * (including another {@code level=null} entry) — it always renders as its own
     * {@code level_unresolved} row (stage 79-E "level null").
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
        // Defensive: unreachable today (unit is fixed per metric server-side), kept for stage 79-Q completeness.
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
