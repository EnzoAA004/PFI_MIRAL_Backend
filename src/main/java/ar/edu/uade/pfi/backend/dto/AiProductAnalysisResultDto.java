package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.util.List;

/**
 * Etapa 76-N / 78: lo que el Frontend realmente recibe de POST/GET sobre
 * {@code /api/studies/{caseId}/product-analys(is|es)} — construido a partir de las filas persistidas
 * {@code AiProductAnalysisRun}/{@code AiDiscLevelResult}/{@code AiMeasurementResult} más
 * su overlay de revisión profesional (etapa 78), nunca directamente de la respuesta upstream cruda.
 * Deliberadamente excluye la URL base del módulo de IA, los
 * {@code sagittalInputId}/{@code axialInputId} resueltos y cualquier SHA de checkpoint de modelo — nada de eso
 * lo necesita el Frontend y no se vuelve a exponer acá.
 *
 * <p>{@code discLevelId}/{@code measurementId} SÍ se exponen (a diferencia de los campos de arriba) — son
 * identificadores opacos generados por el Backend, sin significado clínico ni de infraestructura, y el
 * Frontend los necesita para direccionar los endpoints de revisión/corrección de la etapa 78.
 */
public record AiProductAnalysisResultDto(
    String analysisRunId,
    String studyId,
    String caseId,
    String status,
    List<String> warnings,
    List<DiscLevelView> discLevels,
    ReviewView review,
    boolean humanReviewRequired,
    boolean notClinicalDiagnosis,
    boolean autonomousDiagnosis,
    Instant createdAt,
    Instant completedAt
) {

    public record DiscLevelView(
        String discLevelId,
        String level,
        String localizationStatus,
        boolean localizationValidated,
        boolean abstentionPreserved,
        String axialPairingStatus,
        boolean axialPairingValidated,
        boolean axialPairingExperimental,
        boolean axialPairingReviewRequired,
        String degenerativeFindingsStatus,
        boolean automaticInferenceEnabled,
        List<MeasurementView> measurements,
        ReviewView review
    ) {
    }

    public record MeasurementView(
        String measurementId,
        String plane,
        String level,
        String structure,
        Double areaMm2,
        Double majorAxisMm,
        Double minorAxisMm,
        boolean reviewRequired,
        List<MeasurementCorrectionView> corrections
    ) {
    }

    /** La corrección profesional vigente de un campo (etapa 78-D) — el valor original de la IA siempre se preserva junto a ella. */
    public record MeasurementCorrectionView(
        String field,
        Double originalValue,
        double correctedValue,
        String unit,
        String comment,
        String reviewedBy,
        Instant createdAt
    ) {
    }

    /**
     * Decisión de revisión profesional — reutilizada tal cual para la revisión a nivel de corrida y la de cada nivel de
     * disco (etapa 78-B/78-C). {@code status} es uno de
     * {@code pending|accepted|observed|rejected}; nunca un vocabulario clínico. Este es siempre un
     * concepto distinto de los campos propios de la IA {@code localizationStatus}/{@code axialPairingStatus}/
     * {@code validated} de arriba — aceptar una revisión acá nunca cambia esos.
     */
    public record ReviewView(boolean required, String status, String comment, String reviewedBy, Instant reviewedAt) {
    }
}
