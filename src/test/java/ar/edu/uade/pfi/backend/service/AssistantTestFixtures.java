package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fixtures de contrato para las pruebas del asistente: estudios, corridas con mediciones y hallazgos P10.7 en la forma
 * en que los persiste el análisis inicial. Son datos de prueba, no resultados de ningún estudio.
 */
final class AssistantTestFixtures {
    static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    static final String SHA = "16eccff327e6794b127fe372ecd03ea619a0f69d939b84ae1aa2e904191c6293";

    final InMemoryStudyRepository repository = new InMemoryStudyRepository();

    Study study(String caseId, String patientId, String subjectRef, LocalDate date) {
        Study study = new Study(UUID.randomUUID().toString(), caseId, "registered", patientId, subjectRef, date, "MRI", "desc", "medium", T0, T0);
        repository.saveStudy(study);
        return study;
    }

    /** Estudio con su contexto clínico propio. */
    Study studyWithContext(String caseId, String patientId, LocalDate date, Integer age, String provenance, String reason, String symptoms, String duration, String history) {
        Study study = new Study(
            UUID.randomUUID().toString(), caseId, "registered", patientId, "SUBJ-" + patientId, date, "MRI", "desc", "medium",
            age, provenance, reason, symptoms, duration, history, "pfi.study-context.v1", T0, T0);
        repository.saveStudy(study);
        return study;
    }

    static Map<String, Object> measurement(String id, String labelKey, String level, String unit, Double value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("labelKey", labelKey);
        map.put("level", level);
        map.put("levelScope", level == null ? "study" : "level");
        map.put("unit", unit);
        map.put("value", value);
        map.put("aiValue", value);
        map.put("plane", "sagittal");
        map.put("experimental", false);
        map.put("measurementBasis", "physical_spacing");
        map.put("sliceIndex", 7);
        map.put("source", "AI");
        return map;
    }

    static Map<String, Object> disc(String level, String metric, Double value) {
        return measurement("sagittal-disc-" + level.toLowerCase() + "-" + metric, "disc " + metric, level, metric.equals("area") ? "mm2" : "mm", value);
    }

    /** Un hallazgo P10.7 persistido (con el vector crudo de probabilidades). */
    static Map<String, Object> finding(String level, String type, String label, Map<String, Double> probabilities) {
        Map<String, Object> classification = new LinkedHashMap<>();
        boolean categorical = type.equals("pfirrmann_grade") || type.equals("modic_change");
        classification.put("kind", categorical ? "categorical" : "binary");
        classification.put("label", label);
        classification.put("probabilities", probabilities);
        String deployment = switch (type) {
            case "disc_bulging", "disc_narrowing", "upper_endplate_change", "lower_endplate_change" -> "supported_internal";
            case "pfirrmann_grade" -> "experimental";
            default -> "not_product_supported";
        };
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("findingId", UUID.randomUUID().toString());
        finding.put("findingType", type);
        finding.put("anatomy", Map.of("level", level));
        finding.put("classification", classification);
        finding.put("evidence", Map.of("deploymentStatus", deployment, "evaluationDataset", "SPIDER_internal_test", "externalValidationAvailable", false));
        finding.put("localization", Map.of("source", "segmentation_derived_disc_level", "researchOnly", true, "automaticAnatomicalLocalizationValidated", false));
        finding.put("model", Map.of("modelId", "spider_degenerative_multitask_sagittal_t1_t2_2p5d", "modelSha256", SHA));
        finding.put("review", Map.of("required", true, "status", "pending"));
        finding.put("notClinicalDiagnosis", true);
        finding.put("sourceSeries", List.of(
            Map.of("role", "sagittal_t1", "available", true, "positions", List.of(3, 4, 5)),
            Map.of("role", "sagittal_t2", "available", true, "positions", List.of(3, 4, 5))));
        return finding;
    }

    static Map<String, Object> bulging(String level, boolean present, double probability) {
        double other = 1 - probability;
        return finding(level, "disc_bulging", present ? "present" : "absent",
            present ? Map.of("absent", other, "present", probability) : Map.of("absent", probability, "present", other));
    }

    InputResource input(Study study, String inputId, String weighting) {
        InputResource input = new InputResource(UUID.randomUUID().toString(), study.id(), "sagittal", inputId, "dcm", 10, "t2_tse_sag", weighting, 15, false, false, true, T0);
        repository.saveInput(input);
        return input;
    }

    StudyRun run(Study study, String runId, Instant createdAt, List<Map<String, Object>> sagittal, List<Map<String, Object>> findings) {
        input(study, "inp-" + runId, "t2");
        Map<String, Object> sagittalPlane = new LinkedHashMap<>();
        sagittalPlane.put("measurements", sagittal);
        sagittalPlane.put("input", Map.of("inputId", "inp-" + runId));
        sagittalPlane.put("synthetic", false);
        Map<String, Object> planes = new LinkedHashMap<>();
        planes.put("sagittal", sagittalPlane);
        planes.put("axial", Map.of("measurements", List.of()));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("planes", planes);
        if (findings != null) {
            snapshot.put("discDegenerativeFindings", Map.of("schemaVersion", DiscFindingsComparator.SCHEMA_VERSION, "findings", new ArrayList<>(findings)));
            snapshot.put("discDegenerativeGovernance", Map.of(
                "humanReviewRequired", true, "notClinicalDiagnosis", true, "autonomousDiagnosis", false,
                "predictionImmutable", true, "reviewStoredSeparately", true));
        }
        StudyRun run = new StudyRun(
            UUID.randomUUID().toString(), study.id(), runId, "trace-" + runId, "real_baseline", "real_baseline",
            "sagittal_spider", "axial_t2_alkafri", "hash-1", "", "sag-" + runId, "ax-" + runId,
            Map.of(), snapshot, List.of(), "completed", "pending", "", null, "", createdAt, createdAt);
        repository.saveRun(run);
        return run;
    }

    void correct(StudyRun run, String measurementId, double correctedValue, String unit) {
        repository.saveReview(run.multiplanarRunId(), "observed", "Dra. Test", T0.plusSeconds(60), "nota",
            List.of(new MeasurementCorrection(UUID.randomUUID().toString(), run.id(), measurementId, "x", Map.of("value", 0), Map.of("value", correctedValue, "unit", unit), "", T0.plusSeconds(60))));
    }

}
