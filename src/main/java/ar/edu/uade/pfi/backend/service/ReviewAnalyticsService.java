package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegionCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.EvidenceGeometry;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * EXT-02: computes {@link ReviewAnalyticsReport} entirely from data this product already
 * persists (Stage 78-D measurement corrections, EXT-04B2/EXT-04B5 evidence region corrections and
 * review status via {@link EffectiveAnnotationResolver}) — no new AI capability, no new
 * measurement of "accuracy", only a description of how often and by how much a professional's
 * value diverges from the AI's own output. Read-only: nothing here writes anything.
 */
@Service
public class ReviewAnalyticsService {
    private final StudyRepository repository;
    private final EffectiveAnnotationResolver annotationResolver;
    private final Clock clock;

    @Autowired
    public ReviewAnalyticsService(StudyRepository repository, EffectiveAnnotationResolver annotationResolver) {
        this(repository, annotationResolver, Clock.systemUTC());
    }

    ReviewAnalyticsService(StudyRepository repository, EffectiveAnnotationResolver annotationResolver, Clock clock) {
        this.repository = repository;
        this.annotationResolver = annotationResolver;
        this.clock = clock;
    }

    /** {@code caseId == null} aggregates across every study this Backend has ever persisted. */
    public ReviewAnalyticsReport computeReport(String caseId) {
        List<Study> studies = caseId == null
            ? repository.findAllStudies()
            : repository.findStudyByCaseId(caseId).map(List::of).orElse(List.of());

        List<MeasurementInstance> measurementInstances = new ArrayList<>();
        List<AiEvidenceRegion> allRegions = new ArrayList<>();
        List<AiEvidenceRegionCorrection> allRegionCorrections = new ArrayList<>();
        Map<String, String> regionReviewStatus = new HashMap<>();

        for (Study study : studies) {
            for (AiProductAnalysisRun run : repository.findAiProductAnalysisRunsByStudyId(study.id())) {
                Map<String, AiMeasurementResult> measurementsById = new HashMap<>();
                for (AiMeasurementResult measurement : repository.findAiMeasurementResultsByAnalysisRunId(run.id())) {
                    measurementsById.put(measurement.id(), measurement);
                    measurementInstances.addAll(measurementInstances(measurement, run));
                }
                Map<String, List<AiMeasurementCorrection>> correctionsByMeasurementAndField = new HashMap<>();
                for (AiMeasurementCorrection correction : repository.findAiMeasurementCorrectionsByAnalysisRunId(run.id())) {
                    correctionsByMeasurementAndField
                        .computeIfAbsent(correction.measurementId() + "|" + correction.field(), key -> new ArrayList<>())
                        .add(correction);
                }
                applyCorrections(measurementInstances, correctionsByMeasurementAndField);

                List<AiEvidenceRegion> regions = repository.findAiEvidenceRegionsByAnalysisRunId(run.id());
                allRegions.addAll(regions);
                allRegionCorrections.addAll(repository.findAiEvidenceRegionCorrectionsByAnalysisRunId(run.id()));
                for (EffectiveAnnotationResolver.EffectiveAnnotation annotation : annotationResolver.effectiveAnnotations(run, true)) {
                    regionReviewStatus.put(annotation.region().id(), annotation.reviewStatus());
                }
            }
        }

        return new ReviewAnalyticsReport(
            "pfi.review-analytics.v1",
            java.time.Instant.now(clock),
            measurementAnalytics(measurementInstances),
            evidenceRegionAnalytics(allRegions, allRegionCorrections, regionReviewStatus),
            List.of("Sin agregación de clasificación: no existe en este producto un contrato estable de corrección de clasificación (mismo hallazgo que EXT-05) — no se inventa una métrica para eso.",
                "Intersection-over-Union solo se calcula para pares BBOX→BBOX; ELLIPSE→ELLIPSE reporta desplazamiento de centroide y delta de área, pero no IoU (no implementado esta etapa).")
        );
    }

    // ---- measurement instances -------------------------------------------------------------

    private record MetricField(String name, String unit, java.util.function.Function<AiMeasurementResult, Double> accessor) {
    }

    private static final List<MetricField> METRIC_FIELDS = List.of(
        new MetricField("areaMm2", "mm²", AiMeasurementResult::areaMm2),
        new MetricField("majorAxisMm", "mm", AiMeasurementResult::majorAxisMm),
        new MetricField("minorAxisMm", "mm", AiMeasurementResult::minorAxisMm)
    );

    /** Mutable accumulator — one per (measurement row, metric field) that actually has a value. */
    private static final class MeasurementInstance {
        final String measurementId;
        final String level;
        final String plane;
        final String structure;
        final String metric;
        final String unit;
        final String releaseId;
        final double automaticValue;
        Double correctedValue;

        MeasurementInstance(String measurementId, String level, String plane, String structure, String metric, String unit, String releaseId, double automaticValue) {
            this.measurementId = measurementId;
            this.level = level;
            this.plane = plane;
            this.structure = structure;
            this.metric = metric;
            this.unit = unit;
            this.releaseId = releaseId;
            this.automaticValue = automaticValue;
        }
    }

    private List<MeasurementInstance> measurementInstances(AiMeasurementResult measurement, AiProductAnalysisRun run) {
        List<MeasurementInstance> instances = new ArrayList<>();
        for (MetricField field : METRIC_FIELDS) {
            Double value = field.accessor().apply(measurement);
            if (value == null) continue;
            instances.add(new MeasurementInstance(measurement.id(), measurement.level(), measurement.plane(), measurement.structure(), field.name(), field.unit(), run.releaseId(), value));
        }
        return instances;
    }

    private void applyCorrections(List<MeasurementInstance> instances, Map<String, List<AiMeasurementCorrection>> correctionsByKey) {
        for (MeasurementInstance instance : instances) {
            List<AiMeasurementCorrection> corrections = correctionsByKey.get(instance.measurementId + "|" + instance.metric);
            if (corrections == null || corrections.isEmpty()) continue;
            instance.correctedValue = corrections.stream().max(Comparator.comparing(AiMeasurementCorrection::createdAt)).orElseThrow().correctedValue();
        }
    }

    private ReviewAnalyticsReport.MeasurementAnalytics measurementAnalytics(List<MeasurementInstance> instances) {
        Map<String, List<MeasurementInstance>> byGroup = new LinkedHashMap<>();
        for (MeasurementInstance instance : instances) {
            String key = String.join("|", nullSafe(instance.level), nullSafe(instance.plane), nullSafe(instance.structure), instance.metric, instance.unit, nullSafe(instance.releaseId));
            byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(instance);
        }
        List<ReviewAnalyticsReport.MeasurementGroup> groups = new ArrayList<>();
        int totalCorrected = 0;
        for (List<MeasurementInstance> group : byGroup.values()) {
            MeasurementInstance first = group.get(0);
            List<Double> absoluteErrors = new ArrayList<>();
            List<Double> signedErrors = new ArrayList<>();
            int correctedInGroup = 0;
            for (MeasurementInstance instance : group) {
                if (instance.correctedValue == null) continue;
                correctedInGroup += 1;
                double signed = instance.correctedValue - instance.automaticValue;
                signedErrors.add(signed);
                absoluteErrors.add(Math.abs(signed));
            }
            totalCorrected += correctedInGroup;
            groups.add(new ReviewAnalyticsReport.MeasurementGroup(
                first.level, first.plane, first.structure, first.metric, first.unit, first.releaseId,
                group.size(), correctedInGroup,
                group.isEmpty() ? 0.0 : (double) correctedInGroup / group.size(),
                mean(absoluteErrors), median(absoluteErrors), mean(signedErrors)
            ));
        }
        return new ReviewAnalyticsReport.MeasurementAnalytics(instances.size(), totalCorrected, instances.isEmpty() ? 0.0 : (double) totalCorrected / instances.size(), groups);
    }

    private static Double mean(List<Double> values) {
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static Double median(List<Double> values) {
        if (values.isEmpty()) return null;
        List<Double> sorted = values.stream().sorted().toList();
        int size = sorted.size();
        return size % 2 == 1 ? sorted.get(size / 2) : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    // ---- evidence region analytics ----------------------------------------------------------

    private ReviewAnalyticsReport.EvidenceRegionAnalytics evidenceRegionAnalytics(
        List<AiEvidenceRegion> regions, List<AiEvidenceRegionCorrection> corrections, Map<String, String> reviewStatusByRegionId
    ) {
        Map<String, List<AiEvidenceRegionCorrection>> correctionsByRegion = new HashMap<>();
        for (AiEvidenceRegionCorrection correction : corrections) {
            correctionsByRegion.computeIfAbsent(correction.evidenceRegionId(), key -> new ArrayList<>()).add(correction);
        }

        int aiRegions = 0;
        int accepted = 0;
        int observed = 0;
        int rejected = 0;
        int pending = 0;
        int correctedCount = 0;
        int professionalDrawn = 0;
        List<ReviewAnalyticsReport.GeometryCorrectionDelta> deltas = new ArrayList<>();

        for (AiEvidenceRegion region : regions) {
            if ("professional".equals(region.origin())) {
                if (!correctionsByRegion.containsKey(region.id())) professionalDrawn += 1;
                continue;
            }
            aiRegions += 1;
            List<AiEvidenceRegionCorrection> regionCorrections = correctionsByRegion.get(region.id());
            if (regionCorrections != null && !regionCorrections.isEmpty()) {
                correctedCount += 1;
                AiEvidenceRegionCorrection latest = regionCorrections.stream().max(Comparator.comparing(AiEvidenceRegionCorrection::createdAt)).orElseThrow();
                geometryDelta(region.id(), latest).ifPresent(deltas::add);
            }
            String status = reviewStatusByRegionId.getOrDefault(region.id(), "pending");
            switch (status) {
                case "accepted" -> accepted += 1;
                case "observed" -> observed += 1;
                case "rejected" -> rejected += 1;
                default -> pending += 1;
            }
        }

        return new ReviewAnalyticsReport.EvidenceRegionAnalytics(
            aiRegions, accepted, observed, rejected, pending, correctedCount,
            aiRegions == 0 ? 0.0 : (double) correctedCount / aiRegions, professionalDrawn, deltas
        );
    }

    /** Only produced when original and corrected geometry are the SAME comparable shape — never across shapes, never for MASK_REFERENCE/POLYGON. */
    private Optional<ReviewAnalyticsReport.GeometryCorrectionDelta> geometryDelta(String regionId, AiEvidenceRegionCorrection correction) {
        if (correction.originalGeometryType() == null || correction.originalGeometry() == null) return Optional.empty();
        if (!correction.originalGeometryType().equals(correction.correctedGeometryType())) return Optional.empty();
        EvidenceGeometry original = EvidenceGeometry.fromMap(correction.originalGeometryType(), correction.originalGeometry());
        EvidenceGeometry corrected = EvidenceGeometry.fromMap(correction.correctedGeometryType(), correction.correctedGeometry());

        if (original instanceof EvidenceGeometry.Bbox originalBbox && corrected instanceof EvidenceGeometry.Bbox correctedBbox) {
            double originalCenterX = originalBbox.x() + originalBbox.width() / 2;
            double originalCenterY = originalBbox.y() + originalBbox.height() / 2;
            double correctedCenterX = correctedBbox.x() + correctedBbox.width() / 2;
            double correctedCenterY = correctedBbox.y() + correctedBbox.height() / 2;
            double centroidDisplacement = Math.hypot(correctedCenterX - originalCenterX, correctedCenterY - originalCenterY);
            double areaDelta = (correctedBbox.width() * correctedBbox.height()) - (originalBbox.width() * originalBbox.height());
            return Optional.of(new ReviewAnalyticsReport.GeometryCorrectionDelta(regionId, correction.id(), "BBOX", centroidDisplacement, areaDelta, bboxIou(originalBbox, correctedBbox)));
        }
        if (original instanceof EvidenceGeometry.Ellipse originalEllipse && corrected instanceof EvidenceGeometry.Ellipse correctedEllipse) {
            double centroidDisplacement = Math.hypot(correctedEllipse.centerX() - originalEllipse.centerX(), correctedEllipse.centerY() - originalEllipse.centerY());
            double originalArea = Math.PI * originalEllipse.radiusX() * originalEllipse.radiusY();
            double correctedArea = Math.PI * correctedEllipse.radiusX() * correctedEllipse.radiusY();
            return Optional.of(new ReviewAnalyticsReport.GeometryCorrectionDelta(regionId, correction.id(), "ELLIPSE", centroidDisplacement, correctedArea - originalArea, null));
        }
        // POLYGON/MASK_REFERENCE, or a shape change already filtered above: not comparable, skip.
        return Optional.empty();
    }

    private static double bboxIou(EvidenceGeometry.Bbox a, EvidenceGeometry.Bbox b) {
        double left = Math.max(a.x(), b.x());
        double top = Math.max(a.y(), b.y());
        double right = Math.min(a.x() + a.width(), b.x() + b.width());
        double bottom = Math.min(a.y() + a.height(), b.y() + b.height());
        double intersection = Math.max(0, right - left) * Math.max(0, bottom - top);
        double union = (a.width() * a.height()) + (b.width() * b.height()) - intersection;
        return union <= 0 ? 0.0 : intersection / union;
    }
}
