package ar.edu.uade.pfi.backend.dto;

import java.util.List;

/**
 * Etapa 80-D: el contexto estructurado que {@code StudyChatContextBuilder} le entrega al LLM — nunca al
 * revés. Es deliberadamente mínimo (80-I: "Limitar contexto"), no una re-exportación de
 * {@code AiProductAnalysisResultDto}: sin ids internos ({@code analysisRunId}, {@code studyId},
 * {@code discLevelId}, {@code measurementId}), sin {@code reviewedBy}/{@code reviewedAt}, sin identidad
 * DICOM/paciente cruda de ningún tipo (80-E) — solo lo que una respuesta en lenguaje natural podría realmente
 * necesitar.
 *
 * <p>{@code analysis}/{@code discLevels}/{@code longitudinalComparison} son todos nullable/vacíos
 * cuando no se pidieron o no están disponibles — al LLM se le indica explícitamente (vía el system prompt) que
 * informe la indisponibilidad en lugar de inventarla, y este tipo nunca fabrica un valor placeholder
 * para llenar un hueco (80-O: grounding).
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

    /** {@code level} queda en {@code null} exactamente cuando 67B2 no asignó uno — nunca un placeholder. */
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
     * Reutiliza {@link LongitudinalComparisonResultDto.MeasurementComparison} directamente — la etapa 79
     * ya calculó cada delta de forma determinística; la etapa 80 nunca debe recalcular ni
     * reinterpretar uno (80-D: "NO calcular deltas longitudinales que ya calcula Stage 79").
     */
    public record ChatContextLongitudinal(
        String baselineCaseId,
        String baselineStudyDate,
        String currentStudyDate,
        List<LongitudinalComparisonResultDto.MeasurementComparison> measurements
    ) {
    }
}
