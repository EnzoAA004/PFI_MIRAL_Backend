package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiDiscLevelReview;
import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRunReview;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.ChatContextResult;
import ar.edu.uade.pfi.backend.dto.LongitudinalComparisonResultDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Stage 80-D: builds the structured, minimal context {@code ChatService} hands to the LLM.
 *
 * <p>Never queries FastAPI or raw SQL directly — everything comes through {@link StudyRepository}
 * and the already-existing stage 76-79 services, exactly the same read paths their own GET
 * endpoints use. Never fabricates a fact: an unavailable analysis/comparison simply means a
 * {@code null}/empty section of the context, and the system prompt (not this class) is what tells
 * the LLM to say so (80-O: grounding lives in what's absent from the context, not in a special
 * "unavailable" sentence written here).
 */
@Component
public class StudyChatContextBuilder {
    private static final List<String> MEASUREMENT_FIELDS = List.of("areaMm2", "majorAxisMm", "minorAxisMm");

    private final StudyRepository repository;
    private final AiProductAnalysisPersistenceService persistenceService;
    private final EffectiveAnalysisRunSelector runSelector;
    private final LongitudinalComparisonService longitudinalComparisonService;

    public StudyChatContextBuilder(
        StudyRepository repository,
        AiProductAnalysisPersistenceService persistenceService,
        EffectiveAnalysisRunSelector runSelector,
        LongitudinalComparisonService longitudinalComparisonService
    ) {
        this.repository = repository;
        this.persistenceService = persistenceService;
        this.runSelector = runSelector;
        this.longitudinalComparisonService = longitudinalComparisonService;
    }

    /**
     * @param analysisId explicit analysis to ground the context in; when {@code null}, falls back
     *                    to {@link EffectiveAnalysisRunSelector} (stage 79's own deterministic
     *                    rule) — the two selection rules are intentionally the same one, never a
     *                    second policy invented for chat.
     * @param baselineCaseId when present, adds a longitudinal-comparison section computed by the
     *                        existing stage-79 service — never recomputed here.
     */
    public ChatContextResult build(Study study, String analysisId, String baselineCaseId) {
        List<String> sources = new ArrayList<>();
        AiProductAnalysisRun run = analysisId != null
            ? persistenceService.resolveAnalysisRun(study.caseId(), analysisId)
            : runSelector.selectEffectiveRun(study.id()).orElse(null);

        ChatContextResult.ChatContextAnalysis analysisContext = null;
        List<ChatContextResult.ChatContextDiscLevel> discLevels = List.of();
        if (run != null) {
            sources.add("current_analysis");
            AiProductAnalysisRunReview runReview = repository.findAiProductAnalysisRunReview(run.id()).orElse(null);
            if (runReview != null) sources.add("professional_review");
            analysisContext = new ChatContextResult.ChatContextAnalysis(
                run.status(),
                run.humanReviewRequired(),
                run.notClinicalDiagnosis(),
                run.warnings() == null ? List.of() : run.warnings(),
                runReview == null ? "pending" : runReview.reviewStatus(),
                runReview == null ? null : runReview.reviewComment()
            );
            discLevels = buildDiscLevels(run);
            if (discLevels.stream().anyMatch(this::hasProfessionalCorrection)) {
                sources.add("professional_correction");
            }
        }

        ChatContextResult.ChatContextLongitudinal longitudinal = null;
        if (baselineCaseId != null) {
            LongitudinalComparisonResultDto comparison = longitudinalComparisonService.compare(study.caseId(), baselineCaseId);
            longitudinal = new ChatContextResult.ChatContextLongitudinal(
                comparison.baselineCaseId(),
                comparison.baselineStudyDate() == null ? null : comparison.baselineStudyDate().toString(),
                comparison.currentStudyDate() == null ? null : comparison.currentStudyDate().toString(),
                comparison.measurements()
            );
            sources.add("longitudinal_comparison");
        }

        ChatContextResult.ChatContextStudy studyContext = new ChatContextResult.ChatContextStudy(
            study.caseId(), study.studyDate() == null ? null : study.studyDate().toString(), study.ageAtStudy(), study.ageProvenance(), study.clinicalReason(), study.symptoms(), study.symptomDuration(), study.relevantHistory(), study.contextVersion()
        );
        if (study.ageAtStudy() != null || study.clinicalReason() != null || study.symptoms() != null || study.symptomDuration() != null || study.relevantHistory() != null) sources.add("study_clinical_context");
        return new ChatContextResult(studyContext, analysisContext, discLevels, longitudinal, sources);
    }

    private boolean hasProfessionalCorrection(ChatContextResult.ChatContextDiscLevel discLevel) {
        return discLevel.measurements().stream().anyMatch(m -> "professional_correction".equals(m.effectiveSource()));
    }

    private List<ChatContextResult.ChatContextDiscLevel> buildDiscLevels(AiProductAnalysisRun run) {
        List<AiDiscLevelResult> levels = repository.findAiDiscLevelResultsByAnalysisRunId(run.id());
        List<AiMeasurementResult> measurements = repository.findAiMeasurementResultsByAnalysisRunId(run.id());
        List<AiDiscLevelReview> levelReviews = repository.findAiDiscLevelReviewsByAnalysisRunId(run.id());
        List<AiMeasurementCorrection> corrections = repository.findAiMeasurementCorrectionsByAnalysisRunId(run.id());

        Map<String, AiDiscLevelReview> reviewsByLevelId = new HashMap<>();
        for (AiDiscLevelReview review : levelReviews) reviewsByLevelId.put(review.discLevelResultId(), review);
        Map<String, List<AiMeasurementCorrection>> correctionsByMeasurement = new HashMap<>();
        for (AiMeasurementCorrection correction : corrections) {
            correctionsByMeasurement.computeIfAbsent(correction.measurementId(), key -> new ArrayList<>()).add(correction);
        }
        Map<String, List<AiMeasurementResult>> measurementsByDiscLevel = new HashMap<>();
        for (AiMeasurementResult measurement : measurements) {
            measurementsByDiscLevel.computeIfAbsent(measurement.discLevelResultId(), key -> new ArrayList<>()).add(measurement);
        }

        List<ChatContextResult.ChatContextDiscLevel> result = new ArrayList<>();
        for (AiDiscLevelResult level : levels) {
            AiDiscLevelReview review = reviewsByLevelId.get(level.id());
            List<ChatContextResult.ChatContextMeasurement> levelMeasurements = new ArrayList<>();
            for (AiMeasurementResult measurement : measurementsByDiscLevel.getOrDefault(level.id(), List.of())) {
                levelMeasurements.addAll(effectiveMeasurements(measurement, correctionsByMeasurement.getOrDefault(measurement.id(), List.of())));
            }
            result.add(new ChatContextResult.ChatContextDiscLevel(
                level.level(),
                level.localizationStatus(),
                level.axialPairingStatus(),
                level.axialPairingValidated(),
                level.axialPairingExperimental(),
                level.degenerativeFindingsStatus(),
                review == null ? "pending" : review.reviewStatus(),
                levelMeasurements
            ));
        }
        return result;
    }

    private List<ChatContextResult.ChatContextMeasurement> effectiveMeasurements(AiMeasurementResult measurement, List<AiMeasurementCorrection> measurementCorrections) {
        List<ChatContextResult.ChatContextMeasurement> result = new ArrayList<>();
        for (String field : MEASUREMENT_FIELDS) {
            Double automatic = valueFor(measurement, field);
            AiMeasurementCorrection latest = latestCorrectionForField(measurementCorrections, field);
            if (automatic == null && latest == null) continue;
            Double corrected = latest == null ? null : latest.correctedValue();
            Double effective = corrected != null ? corrected : automatic;
            String source = corrected != null ? "professional_correction" : "ai_original";
            result.add(new ChatContextResult.ChatContextMeasurement(field, automatic, corrected, effective, source, unitFor(field)));
        }
        return result;
    }

    private AiMeasurementCorrection latestCorrectionForField(List<AiMeasurementCorrection> corrections, String field) {
        AiMeasurementCorrection latest = null;
        for (AiMeasurementCorrection correction : corrections) {
            if (!field.equals(correction.field())) continue;
            if (latest == null || correction.createdAt().isAfter(latest.createdAt())) latest = correction;
        }
        return latest;
    }

    private Double valueFor(AiMeasurementResult measurement, String field) {
        return switch (field) {
            case "areaMm2" -> measurement.areaMm2();
            case "majorAxisMm" -> measurement.majorAxisMm();
            case "minorAxisMm" -> measurement.minorAxisMm();
            default -> throw new IllegalStateException("unreachable: unknown measurement field " + field);
        };
    }

    private String unitFor(String field) {
        return "areaMm2".equals(field) ? "mm2" : "mm";
    }
}
