package ar.edu.uade.pfi.backend.dto;

import java.util.List;

/**
 * Stage 80-D: the structured context {@code StudyChatContextBuilder} hands to the LLM — never the
 * other way around. This is deliberately minimal (80-I: "Limitar contexto"), not a re-export of
 * {@code AiProductAnalysisResultDto}: no internal ids ({@code analysisRunId}, {@code studyId},
 * {@code discLevelId}, {@code measurementId}), no {@code reviewedBy}/{@code reviewedAt}, no raw
 * DICOM/patient identity of any kind (80-E) — only what a natural-language answer could actually
 * need.
 *
 * <p>{@code analysis}/{@code discLevels}/{@code longitudinalComparison} are all nullable/empty
 * when not requested or not available — the LLM is told explicitly (via the system prompt) to
 * report unavailability rather than invent it, and this type never fabricates a placeholder
 * value to fill a gap (80-O: grounding).
 */
public record ChatContextResult(
    ChatContextStudy study,
    ChatContextAnalysis analysis,
    List<ChatContextDiscLevel> discLevels,
    ChatContextLongitudinal longitudinalComparison,
    List<String> contextSources
) {

    public record ChatContextStudy(String caseId, String studyDate, Integer ageAtStudy, String ageProvenance, String clinicalReason, String symptoms, String symptomDuration, String relevantHistory, String contextVersion) {
    }

    public record ChatContextAnalysis(
        String status,
        boolean humanReviewRequired,
        boolean notClinicalDiagnosis,
        List<String> warnings,
        String reviewStatus,
        String reviewComment
    ) {
    }

    public record ChatContextMeasurement(
        String metric,
        Double automaticValue,
        Double correctedValue,
        Double effectiveValue,
        String effectiveSource,
        String unit
    ) {
    }

    /** {@code level} stays {@code null} exactly when 67B2 didn't assign one — never a placeholder. */
    public record ChatContextDiscLevel(
        String level,
        String localizationStatus,
        String axialPairingStatus,
        boolean axialPairingValidated,
        boolean axialPairingExperimental,
        String degenerativeFindingsStatus,
        String reviewStatus,
        List<ChatContextMeasurement> measurements
    ) {
    }

    /**
     * Reuses {@link LongitudinalComparisonResultDto.MeasurementComparison} directly — stage 79
     * already computed every delta deterministically; stage 80 must never recompute or
     * reinterpret one (80-D: "NO calcular deltas longitudinales que ya calcula Stage 79").
     */
    public record ChatContextLongitudinal(
        String baselineCaseId,
        String baselineStudyDate,
        String currentStudyDate,
        List<LongitudinalComparisonResultDto.MeasurementComparison> measurements
    ) {
    }
}
