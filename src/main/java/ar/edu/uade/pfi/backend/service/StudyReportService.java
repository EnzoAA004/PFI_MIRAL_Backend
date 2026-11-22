package ar.edu.uade.pfi.backend.service;
import ar.edu.uade.pfi.backend.service.exception.RunReviewException;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiDiscLevelReview;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRunReview;
import ar.edu.uade.pfi.backend.domain.DerivedEvidenceOrigin;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.LongitudinalComparisonResultDto;
import ar.edu.uade.pfi.backend.dto.StudyReportDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class StudyReportService {
    public static final String SCHEMA_VERSION = "pfi.study-report.v1";
    public static final String DISCLAIMER = "Este informe corresponde a un prototipo de apoyo al análisis y no constituye un diagnóstico médico autónomo.";

    private final StudyRepository repository;
    private final EffectiveAnalysisRunSelector runSelector;
    private final EffectiveMeasurementResolver measurementResolver;
    private final EffectiveAnnotationResolver annotationResolver;
    private final LongitudinalComparisonService longitudinalComparisonService;
    private final StudyReportPdfRenderer pdfRenderer;
    private final Clock clock;

    @Autowired
    public StudyReportService(
        StudyRepository repository,
        EffectiveAnalysisRunSelector runSelector,
        EffectiveMeasurementResolver measurementResolver,
        EffectiveAnnotationResolver annotationResolver,
        LongitudinalComparisonService longitudinalComparisonService,
        StudyReportPdfRenderer pdfRenderer
    ) {
        this(repository, runSelector, measurementResolver, annotationResolver, longitudinalComparisonService, pdfRenderer, Clock.systemUTC());
    }

    StudyReportService(
        StudyRepository repository,
        EffectiveAnalysisRunSelector runSelector,
        EffectiveMeasurementResolver measurementResolver,
        EffectiveAnnotationResolver annotationResolver,
        LongitudinalComparisonService longitudinalComparisonService,
        StudyReportPdfRenderer pdfRenderer,
        Clock clock
    ) {
        this.repository = repository;
        this.runSelector = runSelector;
        this.measurementResolver = measurementResolver;
        this.annotationResolver = annotationResolver;
        this.longitudinalComparisonService = longitudinalComparisonService;
        this.pdfRenderer = pdfRenderer;
        this.clock = clock;
    }

    public StudyReportDto report(String caseId, String baselineCaseId) {
        Study study = repository.findStudyByCaseId(caseId).orElseThrow(() -> new StudyNotFoundException(caseId));
        Instant generatedAt = clock.instant();
        List<AiProductAnalysisRun> runs = repository.findAiProductAnalysisRunsByStudyId(study.id());
        AiProductAnalysisRun effectiveRun = runSelector.selectEffectiveRun(study.id()).orElse(null);
        if (effectiveRun == null) {
            return noEffectiveAnalysisReport(study, runs, generatedAt);
        }

        StudyReportDto.AnalysisSummary analysis = new StudyReportDto.AnalysisSummary(
            effectiveRun.id(),
            effectiveRun.status(),
            effectiveRun.releaseId(),
            effectiveRun.createdAt(),
            effectiveRun.completedAt(),
            effectiveRun.humanReviewRequired()
        );
        StudyReportDto.ReviewSummary review = repository.findAiProductAnalysisRunReview(effectiveRun.id())
            .map(this::toReviewSummary)
            .orElse(new StudyReportDto.ReviewSummary("pending", null, null));
        List<StudyReportDto.DiscLevelSummary> discLevels = discLevels(effectiveRun);
        List<StudyReportDto.MeasurementSummary> measurements = measurementResolver.effectiveMeasurements(effectiveRun).stream()
            .map(this::toMeasurementSummary)
            .sorted(Comparator
                .comparing(StudyReportDto.MeasurementSummary::level, Comparator.nullsLast(String::compareTo))
                .thenComparing(StudyReportDto.MeasurementSummary::plane, Comparator.nullsLast(String::compareTo))
                .thenComparing(StudyReportDto.MeasurementSummary::structure, Comparator.nullsLast(String::compareTo))
                .thenComparing(StudyReportDto.MeasurementSummary::metric, Comparator.nullsLast(String::compareTo)))
            .toList();
        LongitudinalComparisonResultDto longitudinal = hasText(baselineCaseId)
            ? longitudinalComparisonService.compare(caseId, baselineCaseId.trim())
            : null;
        StudyReportDto.LongitudinalHistorySummary longitudinalHistory = longitudinalHistory(study, effectiveRun);
        List<StudyReportDto.VisualEvidenceSummary> visualEvidence = visualEvidence(effectiveRun);
        List<StudyReportDto.BeforeAfterEvidencePairDto> beforeAfterEvidence = beforeAfterEvidence(longitudinalHistory, visualEvidence);

        return new StudyReportDto(
            SCHEMA_VERSION,
            generatedAt,
            "ready",
            study.caseId(),
            study.studyDate(),
            study.modality(),
            analysis,
            review,
            discLevels,
            measurements,
            warnings(effectiveRun),
            limitations(effectiveRun, discLevels),
            longitudinal,
            longitudinalHistory,
            visualEvidence,
            beforeAfterEvidence,
            new StudyReportDto.SafetySummary(
                effectiveRun.humanReviewRequired(),
                effectiveRun.notClinicalDiagnosis(),
                effectiveRun.autonomousDiagnosis(),
                DISCLAIMER
            ),
            new StudyReportDto.TraceabilitySummary(
                study.caseId(),
                effectiveRun.id(),
                effectiveRun.schemaVersion(),
                effectiveRun.releaseId(),
                generatedAt
            )
        );
    }

    public byte[] reportPdf(String caseId, String baselineCaseId) {
        return pdfRenderer.render(report(caseId, baselineCaseId));
    }

    private StudyReportDto noEffectiveAnalysisReport(Study study, List<AiProductAnalysisRun> runs, Instant generatedAt) {
        String latestStatus = runs.stream().findFirst().map(AiProductAnalysisRun::status).orElse("not_available");
        StudyReportDto.AnalysisSummary analysis = new StudyReportDto.AnalysisSummary(
            null,
            latestStatus,
            null,
            null,
            null,
            true
        );
        return new StudyReportDto(
            SCHEMA_VERSION,
            generatedAt,
            "no_effective_analysis",
            study.caseId(),
            study.studyDate(),
            study.modality(),
            analysis,
            new StudyReportDto.ReviewSummary("pending", null, null),
            List.of(),
            List.of(),
            List.of(),
            List.of("No hay una corrida completed/partial utilizable para generar resultados estructurados.", DISCLAIMER),
            null,
            noHistory("No hay una corrida efectiva actual para calcular historial longitudinal.", study, null),
            List.of(),
            List.of(),
            new StudyReportDto.SafetySummary(true, true, false, DISCLAIMER),
            new StudyReportDto.TraceabilitySummary(study.caseId(), null, null, null, generatedAt)
        );
    }

    private StudyReportDto.LongitudinalHistorySummary longitudinalHistory(Study current, AiProductAnalysisRun currentRun) {
        String currentSubject = normalizedSubjectRef(current.subjectRef());
        if (currentSubject == null) {
            return noHistory("No existen estudios previos comparables disponibles.", current, currentRun);
        }

        List<Study> previousStudies = repository.findStudiesBySubjectRef(currentSubject).stream()
            .filter(study -> !study.id().equals(current.id()))
            .filter(study -> isPreviousStudy(study, current))
            .sorted(Comparator
                .comparing((Study study) -> study.studyDate() == null)
                .thenComparing(Study::studyDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Study::createdAt, Comparator.reverseOrder()))
            .toList();
        if (previousStudies.isEmpty()) {
            return noHistory("No existen estudios previos comparables disponibles.", current, currentRun);
        }

        List<StudyReportDto.PreviousStudySummary> previous = new ArrayList<>();
        List<StudyReportDto.LongitudinalHistoryComparison> rows = new ArrayList<>();
        for (Study prior : previousStudies) {
            try {
                LongitudinalComparisonResultDto comparison = longitudinalComparisonService.compare(current.caseId(), prior.caseId());
                List<LongitudinalComparisonResultDto.MeasurementComparison> comparable = comparison.measurements().stream()
                    .filter(item -> "comparable".equals(item.comparisonStatus()))
                    .toList();
                previous.add(new StudyReportDto.PreviousStudySummary(
                    prior.caseId(),
                    prior.studyDate(),
                    comparison.baselineAnalysisRunId(),
                    comparable.isEmpty() ? "no_comparable_measurements" : "comparable",
                    comparable.size(),
                    comparison.measurements().size() - comparable.size()
                ));
                comparable.stream().map(item -> toHistoryRow(comparison, item)).forEach(rows::add);
            } catch (RunReviewException ex) {
                previous.add(new StudyReportDto.PreviousStudySummary(
                    prior.caseId(),
                    prior.studyDate(),
                    null,
                    "no_usable_analysis",
                    0,
                    0
                ));
            }
        }

        String message = rows.isEmpty()
            ? "Existen estudios previos, pero no hay métricas comparables disponibles."
            : "Historial longitudinal técnico disponible.";
        return new StudyReportDto.LongitudinalHistorySummary(
            !rows.isEmpty(),
            new StudyReportDto.CurrentStudySummary(current.caseId(), current.studyDate(), currentRun.id()),
            previous,
            rows,
            message
        );
    }

    private StudyReportDto.LongitudinalHistoryComparison toHistoryRow(
        LongitudinalComparisonResultDto comparison,
        LongitudinalComparisonResultDto.MeasurementComparison item
    ) {
        return new StudyReportDto.LongitudinalHistoryComparison(
            comparison.baselineCaseId(),
            comparison.baselineStudyDate(),
            comparison.currentStudyDate(),
            item.level(),
            item.plane(),
            item.structure(),
            item.metric(),
            displayUnit(item.unit()),
            item.baseline() == null ? null : item.baseline().effectiveValue(),
            item.current() == null ? null : item.current().effectiveValue(),
            item.deltaAbsolute(),
            item.deltaPercent(),
            item.baseline() == null ? null : item.baseline().source(),
            item.current() == null ? null : item.current().source(),
            item.baseline() == null ? null : item.baseline().automaticValue(),
            item.current() == null ? null : item.current().automaticValue(),
            item.baseline() == null ? null : item.baseline().correctedValue(),
            item.current() == null ? null : item.current().correctedValue()
        );
    }

    private StudyReportDto.LongitudinalHistorySummary noHistory(String message, Study current, AiProductAnalysisRun currentRun) {
        return new StudyReportDto.LongitudinalHistorySummary(
            false,
            new StudyReportDto.CurrentStudySummary(current.caseId(), current.studyDate(), currentRun == null ? null : currentRun.id()),
            List.of(),
            List.of(),
            message
        );
    }

    private StudyReportDto.ReviewSummary toReviewSummary(AiProductAnalysisRunReview review) {
        return new StudyReportDto.ReviewSummary(review.reviewStatus(), review.reviewComment(), review.reviewedAt());
    }

    private List<StudyReportDto.DiscLevelSummary> discLevels(AiProductAnalysisRun run) {
        Map<String, AiDiscLevelReview> reviews = new LinkedHashMap<>();
        for (AiDiscLevelReview review : repository.findAiDiscLevelReviewsByAnalysisRunId(run.id())) {
            reviews.put(review.discLevelResultId(), review);
        }
        return repository.findAiDiscLevelResultsByAnalysisRunId(run.id()).stream()
            .map(level -> toDiscLevelSummary(level, reviews.get(level.id())))
            .toList();
    }

    private StudyReportDto.DiscLevelSummary toDiscLevelSummary(AiDiscLevelResult level, AiDiscLevelReview review) {
        return new StudyReportDto.DiscLevelSummary(
            level.id(),
            level.rankIndex(),
            level.level(),
            level.localizationStatus(),
            level.localizationSource(),
            level.localizationValidated(),
            level.abstentionPreserved(),
            level.axialPairingStatus(),
            level.axialPairingValidated(),
            level.axialPairingExperimental(),
            level.axialPairingReviewRequired(),
            level.degenerativeFindingsStatus(),
            level.automaticInferenceEnabled(),
            review == null ? "pending" : review.reviewStatus(),
            review == null ? null : review.reviewComment(),
            review == null ? null : review.reviewedAt()
        );
    }

    private StudyReportDto.MeasurementSummary toMeasurementSummary(EffectiveMeasurementResolver.EffectiveMeasurement measurement) {
        return new StudyReportDto.MeasurementSummary(
            measurement.level(),
            measurement.plane(),
            measurement.structure(),
            measurement.metric(),
            displayUnit(measurement.unit()),
            measurement.automaticValue(),
            measurement.correctedValue(),
            measurement.effectiveValue(),
            measurement.source()
        );
    }

    /**
     * EXT-04B2: solo las regiones actualmente efectivas se muestran en el reporte (replica
     * {@code MeasurementSummary}, que también muestra siempre solo el valor efectivo, nunca un
     * historial de correcciones) — las regiones rechazadas/aún pendientes y bloqueadas siguen consultables a través de
     * sus propias filas persistidas, pero no forman parte de esta "vista actual". Las sugerencias de IA
     * sin revisar SÍ se incluyen (mismo precedente que {@code MeasurementSummary}, que muestra
     * mediciones originales de la IA también antes de cualquier revisión), cada una con su propio
     * {@code reviewStatus} y una advertencia neutral para que el lector pueda distinguirla de una
     * región validada.
     */
    private List<StudyReportDto.VisualEvidenceSummary> visualEvidence(AiProductAnalysisRun run) {
        return annotationResolver.effectiveAnnotations(run, true).stream()
            .filter(EffectiveAnnotationResolver.EffectiveAnnotation::effective)
            .map(annotation -> toVisualEvidenceSummary(annotation, run))
            .toList();
    }

    private StudyReportDto.VisualEvidenceSummary toVisualEvidenceSummary(EffectiveAnnotationResolver.EffectiveAnnotation annotation, AiProductAnalysisRun run) {
        var region = annotation.region();
        String source = switch (annotation.derivedOrigin()) {
            case AI_SUGGESTED -> "ai_suggested";
            case PROFESSIONAL_VALIDATED -> "ai_validated";
            case PROFESSIONAL_CORRECTED -> "professional_corrected";
            case PROFESSIONAL_DRAWN -> "professional_drawn";
        };
        String warning = annotation.derivedOrigin() == DerivedEvidenceOrigin.AI_SUGGESTED
            ? "Sugerencia de IA sin revisión profesional."
            : null;
        return new StudyReportDto.VisualEvidenceSummary(
            region.id(),
            region.plane(),
            region.level(),
            region.structure(),
            annotation.effectiveGeometryType(),
            annotation.effectiveGeometry(),
            source,
            annotation.reviewStatus(),
            region.label(),
            warning,
            region.experimental(),
            baseImageRef(region, run)
        );
    }

    /**
     * EXT-04B3.5: resuelve la referencia a la imagen base de una región a partir de datos que ya existen en su
     * {@link AiProductAnalysisRun} — nunca un nuevo sistema de assets, nunca un path del filesystem. Fail-safe
     * por diseño (precedente 89-CONT: una capacidad ausente devuelve {@code null}, nunca fabrica
     * un valor): {@code null} cuando la región no tiene {@code sliceIndex}, cuando {@code plane} no es
     * ni {@code "sagittal"} ni {@code "axial"}, o cuando la corrida no tiene inputId para ese
     * plano (p. ej. una región solo axial en una corrida solo sagital).
     */
    private StudyReportDto.BaseImageRef baseImageRef(ar.edu.uade.pfi.backend.domain.AiEvidenceRegion region, AiProductAnalysisRun run) {
        if (region.sliceIndex() == null) return null;
        String inputId = switch (region.plane() == null ? "" : region.plane()) {
            case "sagittal" -> run.sagittalInputId();
            case "axial" -> run.axialInputId();
            default -> null;
        };
        if (inputId == null || inputId.isBlank()) return null;
        return new StudyReportDto.BaseImageRef(inputId, region.sliceIndex());
    }

    /** El mismo tope que ya aplica la propia sección antes/después del Frontend (EXT-07B). */
    private static final int BEFORE_AFTER_LIMIT = 4;

    /**
     * EXT-04B4: empareja cada fila de {@code longitudinalHistory.comparisons[]} con un
     * {@link StudyReportDto.VisualEvidenceSummary} coincidente del lado anterior/actual, cuando existe uno
     * en el mismo nivel/plano/estructura — equivalente del lado del servidor de lo que el
     * `beforeAfterDisplay.ts#matchingEvidence` del propio Frontend ya hace del lado del cliente (EXT-07B), necesario acá
     * porque la generación del PDF no tiene un cliente que haga ese emparejamiento por ella. Los valores/deltas numéricos
     * siempre se copian textualmente de la fila — nunca se recalculan.
     */
    private List<StudyReportDto.BeforeAfterEvidencePairDto> beforeAfterEvidence(
        StudyReportDto.LongitudinalHistorySummary history,
        List<StudyReportDto.VisualEvidenceSummary> currentEvidence
    ) {
        if (history == null || history.comparisons().isEmpty()) return List.of();
        Map<String, List<StudyReportDto.VisualEvidenceSummary>> previousEvidenceByCaseId = new LinkedHashMap<>();
        List<StudyReportDto.BeforeAfterEvidencePairDto> pairs = new ArrayList<>();
        for (StudyReportDto.LongitudinalHistoryComparison row : history.comparisons().stream().limit(BEFORE_AFTER_LIMIT).toList()) {
            List<StudyReportDto.VisualEvidenceSummary> previousEvidence = previousEvidenceByCaseId
                .computeIfAbsent(row.previousCaseId(), this::visualEvidenceForCaseId);
            pairs.add(new StudyReportDto.BeforeAfterEvidencePairDto(
                row.level(), row.plane(), row.structure(), row.metric(), row.unit(),
                row.previousStudyDate(), row.currentStudyDate(),
                row.previousEffectiveValue(), row.currentEffectiveValue(),
                row.absoluteDelta(), row.percentageDelta(),
                row.previousValueSource(), row.currentValueSource(),
                matchEvidence(previousEvidence, row.level(), row.plane(), row.structure()),
                matchEvidence(currentEvidence, row.level(), row.plane(), row.structure())
            ));
        }
        return pairs;
    }

    private List<StudyReportDto.VisualEvidenceSummary> visualEvidenceForCaseId(String caseId) {
        return repository.findStudyByCaseId(caseId)
            .flatMap(study -> runSelector.selectEffectiveRun(study.id()))
            .map(this::visualEvidence)
            .orElse(List.of());
    }

    private StudyReportDto.VisualEvidenceSummary matchEvidence(
        List<StudyReportDto.VisualEvidenceSummary> evidence, String level, String plane, String structure
    ) {
        return evidence.stream()
            .filter(item -> java.util.Objects.equals(item.level(), level)
                && java.util.Objects.equals(item.plane(), plane)
                && java.util.Objects.equals(item.structure(), structure))
            .findFirst()
            .orElse(null);
    }

    private List<String> warnings(AiProductAnalysisRun run) {
        return run.warnings() == null ? List.of() : run.warnings();
    }

    private List<String> limitations(AiProductAnalysisRun run, List<StudyReportDto.DiscLevelSummary> discLevels) {
        java.util.LinkedHashSet<String> limitations = new java.util.LinkedHashSet<>();
        limitations.add(DISCLAIMER);
        if (run.humanReviewRequired()) {
            limitations.add("La salida requiere revisión humana profesional antes de cualquier uso académico o asistivo.");
        }
        if (run.autonomousDiagnosis()) {
            limitations.add("El flag autonomousDiagnosis=true no habilita diagnóstico clínico autónomo en este reporte.");
        }
        if (discLevels.stream().anyMatch(StudyReportDto.DiscLevelSummary::axialPairingExperimental)) {
            limitations.add("El emparejamiento axial experimental se conserva como limitación y no se reinterpreta como validado.");
        }
        if (discLevels.stream().anyMatch(level -> !level.automaticInferenceEnabled())) {
            limitations.add("Hay niveles con inferencia automática complementaria no disponible.");
        }
        if (discLevels.stream().anyMatch(level -> "unavailable".equals(level.localizationStatus()) || "abstained".equals(level.localizationStatus()))) {
            limitations.add("Hay niveles con localización no disponible o abstención preservada.");
        }
        return List.copyOf(limitations);
    }

    private String displayUnit(String unit) {
        return "mm2".equals(unit) ? "mm²" : unit;
    }

    private String normalizedSubjectRef(String subjectRef) {
        return subjectRef == null || subjectRef.isBlank() ? null : SubjectReferenceValidator.normalize(subjectRef);
    }

    private boolean isPreviousStudy(Study candidate, Study current) {
        if (current.studyDate() == null) return true;
        return candidate.studyDate() != null && candidate.studyDate().isBefore(current.studyDate());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
