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
 * Stage 84-I: a deterministic measurement value across N Studies of the same patient
 * ({@code subjectRef}) — never a new AI/ML computation, never persisted (84 "NO persistir series
 * calculadas", mirroring Stage 79-J's own "never persisted" decision for the two-Study
 * comparison). Reuses {@link EffectiveAnalysisRunSelector} (which run represents a Study) and
 * {@link EffectiveMeasurementResolver} (effective value + matching key) verbatim — this class
 * only adds the "across N Studies, not just two" iteration on top of both.
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
     * Stage 84-M: only keys with at least 2 comparable points are returned — a single-Study
     * "series" isn't a trend, and returning it would just add UI noise (the Frontend can already
     * show a single Study's own measurements directly, no series needed).
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
                if (measurement.level() == null) continue; // 84-K: null level never joins a series.
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
     * Stage 84-F: the series itself is chronological ascending (oldest first) — the opposite
     * order from the studies-list endpoint (studyDate DESC), matching the stage brief's explicit
     * distinction ("Para la lista: studyDate DESC. Para timeline visual: ASC cronológico").
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
                break; // one point per Study for this key -- a Study never contributes two points to the same series.
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

    /** 84-F: deterministic tie-breaker when two Studies share (or both lack) a studyDate. */
    private Comparator<Study> studyDateAscendingThenCaseId() {
        return Comparator
            .comparing(Study::studyDate, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Study::caseId);
    }

    private MeasurementSeriesKeyDto toKeyDto(EffectiveMeasurementResolver.MatchKey key, String unit) {
        return new MeasurementSeriesKeyDto(key.level(), key.plane(), key.structure(), key.metric(), unit);
    }
}
