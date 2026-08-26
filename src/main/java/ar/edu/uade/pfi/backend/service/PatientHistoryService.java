package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRunReview;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class PatientHistoryService {
    private final StudyRepository repository;
    private final EffectiveAnalysisRunSelector runSelector;

    public PatientHistoryService(StudyRepository repository, EffectiveAnalysisRunSelector runSelector) {
        this.repository = repository;
        this.runSelector = runSelector;
    }

    public Map<String, Object> history(String subjectRef) {
        String normalizedSubjectRef = normalizeSubjectRef(subjectRef);
        try {
            List<Study> studies = repository.findStudiesBySubjectRef(normalizedSubjectRef);
            List<Map<String, Object>> items = studies.stream()
                .map(this::toHistoryStudy)
                .toList();
            return Map.of(
                "status", "ok",
                "source", "postgres-domain",
                "dataOrigin", "database",
                "subjectRef", normalizedSubjectRef,
                "deidentified", true,
                "studies", items,
                "summary", summary(items),
                "humanReviewRequired", true,
                "notClinicalDiagnosis", true
            );
        } catch (IllegalStateException ex) {
            throw new DatabaseUnavailableException("Base de datos no disponible para consultar historial longitudinal.");
        }
    }

    private Map<String, Object> toHistoryStudy(Study study) {
        List<StudyRun> runs = repository.findRunsByStudyId(study.id());
        StudyRun latestRun = runs.isEmpty() ? null : runs.get(0);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("caseId", study.caseId());
        item.put("subjectRef", study.subjectRef());
        item.put("studyDate", study.studyDate() == null ? null : study.studyDate().toString());
        item.put("modality", study.modality());
        item.put("description", study.description());
        item.put("priority", ReviewStatusMapper.toApiPriority(study.reviewPriority()));
        item.put("latestRunId", latestRun == null ? null : latestRun.multiplanarRunId());
        item.put("planes", latestRun == null ? List.of() : planes(latestRun));
        item.put("modelKey", latestRun == null ? null : firstNonBlank(latestRun.sagittalModelKey(), latestRun.axialModelKey()));
        item.put("reviewStatus", latestRun == null ? null : ReviewStatusMapper.toApiStatus(latestRun.reviewStatus()));
        item.put("reviewer", latestRun == null || latestRun.reviewer().isBlank() ? null : latestRun.reviewer());
        item.put("reviewedAt", latestRun == null || latestRun.reviewedAt() == null ? null : latestRun.reviewedAt().toString());
        item.put("measurementsByPlane", latestRun == null ? Map.of() : measurementsByPlane(latestRun.metricsSnapshot()));
        item.put("corrections", latestRun == null ? List.of() : corrections(latestRun.id()));
        item.put("createdAt", study.createdAt().toString());
        item.put("updatedAt", study.updatedAt().toString());
        // Stage 84-C: the post-e50 AI product analysis summary (Stage 76/78/79's own concept,
        // distinct from the legacy StudyRun above) always uses EffectiveAnalysisRunSelector --
        // the exact same rule Stage 79's comparison and Stage 80's chat already use -- so a
        // Study's "current" analysis is never a second, divergent selection rule.
        item.put("productAnalysis", productAnalysisSummary(study));
        return item;
    }

    /**
     * Stage 84-D: {@code null} when the Study has no effective run yet -- this is a normal state
     * ("Sin análisis"), never an error, and the Study is never hidden because of it.
     */
    private Map<String, Object> productAnalysisSummary(Study study) {
        return runSelector.selectEffectiveRun(study.id()).map(this::toProductAnalysisSummary).orElse(null);
    }

    private Map<String, Object> toProductAnalysisSummary(AiProductAnalysisRun run) {
        List<AiMeasurementResult> measurements = repository.findAiMeasurementResultsByAnalysisRunId(run.id());
        List<AiMeasurementCorrection> corrections = repository.findAiMeasurementCorrectionsByAnalysisRunId(run.id());
        long correctedMeasurementCount = corrections.stream().map(AiMeasurementCorrection::measurementId).distinct().count();
        String reviewStatus = repository.findAiProductAnalysisRunReview(run.id())
            .map(AiProductAnalysisRunReview::reviewStatus)
            .orElse("pending");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("analysisRunId", run.id());
        summary.put("status", run.status());
        summary.put("reviewStatus", reviewStatus);
        summary.put("measurementCount", measurements.size());
        summary.put("correctedMeasurementCount", (int) correctedMeasurementCount);
        // 84-H: the warnings are shown, never reinterpreted (e.g. 67B2_RUNTIME_PARITY_NOT_PROVEN
        // stays exactly that string -- the Frontend, per Stage 77's convention, is the only layer
        // that maps it to human-readable text).
        summary.put("warnings", run.warnings());
        summary.put("humanReviewRequired", run.humanReviewRequired());
        summary.put("notClinicalDiagnosis", run.notClinicalDiagnosis());
        return summary;
    }

    private Map<String, Object> summary(List<Map<String, Object>> studies) {
        long pending = studies.stream().filter(item -> "pendiente".equals(item.get("reviewStatus"))).count();
        long completed = studies.stream().filter(item -> "aceptado".equals(item.get("reviewStatus"))).count();
        long observed = studies.stream().filter(item -> "observado".equals(item.get("reviewStatus"))).count();
        long withStudyDate = studies.stream().filter(item -> item.get("studyDate") != null).count();
        return Map.of(
            "totalStudies", studies.size(),
            "pending", pending,
            "completed", completed,
            "observed", observed,
            "withStudyDate", withStudyDate
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<Map<String, Object>>> measurementsByPlane(Map<String, Object> metricsSnapshot) {
        Map<String, List<Map<String, Object>>> byPlane = new LinkedHashMap<>();
        Object planesNode = metricsSnapshot.get("planes");
        if (!(planesNode instanceof Map<?, ?> planesMap)) return byPlane;
        for (String plane : List.of("sagittal", "axial")) {
            Object planeNode = planesMap.get(plane);
            if (!(planeNode instanceof Map<?, ?> planeMap)) continue;
            Object measurements = planeMap.get("measurements");
            if (!(measurements instanceof List<?> list)) continue;
            List<Map<String, Object>> normalized = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                Map<String, Object> measurement = new LinkedHashMap<>((Map<String, Object>) raw);
                measurement.putIfAbsent("aiValue", measurement.get("value"));
                measurement.put("plane", plane);
                measurement.putIfAbsent("source", "AI");
                normalized.add(measurement);
            }
            if (!normalized.isEmpty()) byPlane.put(plane, normalized);
        }
        return byPlane;
    }

    private List<Map<String, Object>> corrections(String studyRunId) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (MeasurementCorrection correction : repository.findCorrectionsByStudyRunId(studyRunId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("measurementId", correction.measurementId());
            item.put("label", correction.label());
            item.put("beforeValue", correction.beforeValue());
            item.put("afterValue", correction.afterValue());
            item.put("comment", correction.comment());
            item.put("createdAt", correction.createdAt().toString());
            values.add(item);
        }
        return values;
    }

    private List<String> planes(StudyRun run) {
        Set<String> planes = new LinkedHashSet<>();
        if (run.sagittalRunId() != null && !run.sagittalRunId().isBlank()) planes.add("sagittal");
        if (run.axialRunId() != null && !run.axialRunId().isBlank()) planes.add("axial");
        return List.copyOf(planes);
    }

    private String normalizeSubjectRef(String subjectRef) {
        return SubjectReferenceValidator.normalize(subjectRef);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
