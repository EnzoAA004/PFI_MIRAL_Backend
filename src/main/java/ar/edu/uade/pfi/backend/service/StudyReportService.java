package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiDiscLevelReview;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRunReview;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.LongitudinalComparisonResultDto;
import ar.edu.uade.pfi.backend.dto.StudyReportDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
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
    private final LongitudinalComparisonService longitudinalComparisonService;
    private final StudyReportPdfRenderer pdfRenderer;
    private final Clock clock;

    @Autowired
    public StudyReportService(
        StudyRepository repository,
        EffectiveAnalysisRunSelector runSelector,
        EffectiveMeasurementResolver measurementResolver,
        LongitudinalComparisonService longitudinalComparisonService,
        StudyReportPdfRenderer pdfRenderer
    ) {
        this(repository, runSelector, measurementResolver, longitudinalComparisonService, pdfRenderer, Clock.systemUTC());
    }

    StudyReportService(
        StudyRepository repository,
        EffectiveAnalysisRunSelector runSelector,
        EffectiveMeasurementResolver measurementResolver,
        LongitudinalComparisonService longitudinalComparisonService,
        StudyReportPdfRenderer pdfRenderer,
        Clock clock
    ) {
        this.repository = repository;
        this.runSelector = runSelector;
        this.measurementResolver = measurementResolver;
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
            new StudyReportDto.SafetySummary(true, true, false, DISCLAIMER),
            new StudyReportDto.TraceabilitySummary(study.caseId(), null, null, null, generatedAt)
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
