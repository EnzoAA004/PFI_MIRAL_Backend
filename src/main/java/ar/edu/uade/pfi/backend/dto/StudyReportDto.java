package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Etapa 88: contrato de reporte/exportación a demanda con autoridad en el backend. */
public record StudyReportDto(
    String schemaVersion,
    Instant generatedAt,
    String reportStatus,
    String caseId,
    LocalDate studyDate,
    String modality,
    AnalysisSummary analysis,
    ReviewSummary review,
    List<DiscLevelSummary> discLevels,
    List<MeasurementSummary> measurements,
    List<String> warnings,
    List<String> limitations,
    LongitudinalComparisonResultDto longitudinalComparison,
    LongitudinalHistorySummary longitudinalHistory,
    /**
     * EXT-04B2: aditivo y retrocompatible — siempre presente (una lista vacía cuando no hay
     * evidencia para mostrar, nunca omitido), de modo que un consumidor que solo conoce los campos previos a EXT-04B2
     * siga funcionando sin cambios. Nunca una afirmación diagnóstica: ver {@link VisualEvidenceSummary}.
     */
    List<VisualEvidenceSummary> visualEvidence,
    /**
     * EXT-04B4: aditivo y retrocompatible — siempre presente (vacío cuando no hay un
     * estudio previo comparable, nunca omitido). Una entrada por cada
     * fila de {@code longitudinalHistory.comparisons[]} (mismo tope, misma fuente de verdad para los
     * valores/deltas numéricos — nunca se recalculan acá), cada una opcionalmente emparejada con un
     * {@link VisualEvidenceSummary} coincidente de cada lado cuando existe uno en el mismo nivel/plano/
     * estructura. Lo consume la sección "Comparación con estudio previo" del PDF; el reporte JSON/UI
     * ya tiene esta información repartida entre {@code longitudinalHistory} y
     * {@code visualEvidence} por separado, así que este campo existe puramente para ahorrarle al renderer del PDF
     * volver a derivar el mismo emparejamiento que el Frontend ya hace del lado del cliente (EXT-07B).
     */
    List<BeforeAfterEvidencePairDto> beforeAfterEvidence,
    SafetySummary safety,
    TraceabilitySummary traceability
) {
    public record AnalysisSummary(
        String analysisRunId,
        String analysisStatus,
        String releaseId,
        Instant createdAt,
        Instant completedAt,
        boolean humanReviewRequired
    ) {
    }

    public record ReviewSummary(
        String reviewStatus,
        String reviewComment,
        Instant reviewedAt
    ) {
    }

    public record DiscLevelSummary(
        String discLevelResultId,
        Integer rankIndex,
        String level,
        String localizationStatus,
        String localizationSource,
        boolean localizationValidated,
        boolean abstentionPreserved,
        String axialPairingStatus,
        boolean axialPairingValidated,
        boolean axialPairingExperimental,
        boolean axialPairingReviewRequired,
        String degenerativeFindingsStatus,
        boolean automaticInferenceEnabled,
        String reviewStatus,
        String reviewComment,
        Instant reviewedAt
    ) {
    }

    public record MeasurementSummary(
        String level,
        String plane,
        String structure,
        String metric,
        String unit,
        Double automaticValue,
        Double correctedValue,
        Double effectiveValue,
        String effectiveSource
    ) {
    }

    public record LongitudinalHistorySummary(
        boolean available,
        CurrentStudySummary currentStudy,
        List<PreviousStudySummary> previousStudies,
        List<LongitudinalHistoryComparison> comparisons,
        String message
    ) {
    }

    public record CurrentStudySummary(
        String caseId,
        LocalDate studyDate,
        String analysisRunId
    ) {
    }

    public record PreviousStudySummary(
        String caseId,
        LocalDate studyDate,
        String analysisRunId,
        String comparisonStatus,
        int comparableMeasurementCount,
        int nonComparableMeasurementCount
    ) {
    }

    public record LongitudinalHistoryComparison(
        String previousCaseId,
        LocalDate previousStudyDate,
        LocalDate currentStudyDate,
        String level,
        String plane,
        String structure,
        String metric,
        String unit,
        Double previousEffectiveValue,
        Double currentEffectiveValue,
        Double absoluteDelta,
        Double percentageDelta,
        String previousValueSource,
        String currentValueSource,
        Double previousAutomaticValue,
        Double currentAutomaticValue,
        Double previousCorrectedValue,
        Double currentCorrectedValue
    ) {
    }

    public record SafetySummary(
        boolean humanReviewRequired,
        boolean notClinicalDiagnosis,
        boolean autonomousDiagnosis,
        String disclaimer
    ) {
    }

    public record TraceabilitySummary(
        String caseId,
        String analysisRunId,
        String analysisSchemaVersion,
        String releaseId,
        Instant generatedAt
    ) {
    }

    /**
     * EXT-04B2: una región visual de interés — nunca una afirmación diagnóstica. {@code geometry} es
     * siempre la geometría EFECTIVA ya resuelta (la corregida si la hay, si no la de la IA o la
     * del propio profesional), en la base normalizada canónica 0..256; para
     * {@code geometryType="MASK_REFERENCE"} solo lleva {@code {runId, plane, assetName}},
     * resuelto mediante el endpoint existente {@code /api/ai/assets/{runId}/{plane}/{assetName}}
     * — nunca un path del filesystem, path de bucket ni UID DICOM.
     *
     * <p>{@code source} es uno de {@code ai_suggested|ai_validated|professional_corrected|
     * professional_drawn} — los mismos cuatro conceptos de EXT-04B1, siempre DERIVADOS (nunca un
     * enum persistido de forma independiente). {@code reviewedBy}/{@code authoredBy} nunca se exponen
     * acá — la misma regla que EXT-04A ya aplica a los resúmenes de revisión de corrida/nivel de disco.
     */
    public record VisualEvidenceSummary(
        String evidenceId,
        String plane,
        String level,
        String structure,
        String geometryType,
        Map<String, Object> geometry,
        String source,
        String reviewStatus,
        String label,
        String warning,
        boolean experimental,
        /**
         * EXT-04B3.5: aditivo, admite null — cómo el Frontend obtiene una imagen base real para esta
         * región, reutilizando el endpoint existente y ya testeado {@code GET /api/ai/series/{inputId}/
         * slices/{index}} (Stage 87/89) en lugar de un nuevo mecanismo de assets. {@code null}
         * cuando la corrida de análisis de la propia región no tiene un inputId por plano para {@code plane}, o cuando
         * la región misma no lleva {@code sliceIndex} — una ausencia fail-safe, nunca una
         * referencia fabricada. Ver {@link BaseImageRef}.
         */
        BaseImageRef baseImage
    ) {
    }

    /**
     * EXT-04B3.5: {@code inputId} es el identificador opaco por upload propio del Backend (la misma
     * forma ya usada por {@code /inputs} y {@code AiProductAnalysisRun.sagittalInputId}/
     * {@code axialInputId} — nunca un path del filesystem, host, bucket ni UID DICOM) y
     * {@code sliceIndex} es el mismo índice base 0 que {@code AiBackendService.getSeriesSlice}
     * ya valida. No hay campo {@code plane} acá: un {@code inputId} ya es por plano
     * (uno por plano de {@code AiProductAnalysisRun}), así que sería redundante con
     * {@code VisualEvidenceSummary.plane}.
     */
    public record BaseImageRef(String inputId, Integer sliceIndex) {
    }

    /**
     * EXT-04B4: una fila "Anterior vs Actual" — los campos numéricos se copian textualmente de la
     * fila {@link LongitudinalHistoryComparison} correspondiente (nunca se recalculan), y
     * {@code previousEvidence}/{@code currentEvidence} son {@code null} (nunca fabricados) cuando
     * no existe ningún {@link VisualEvidenceSummary} en el mismo nivel/plano/estructura de ese lado.
     */
    public record BeforeAfterEvidencePairDto(
        String level,
        String plane,
        String structure,
        String metric,
        String unit,
        LocalDate previousStudyDate,
        LocalDate currentStudyDate,
        Double previousEffectiveValue,
        Double currentEffectiveValue,
        Double absoluteDelta,
        Double percentageDelta,
        String previousValueSource,
        String currentValueSource,
        VisualEvidenceSummary previousEvidence,
        VisualEvidenceSummary currentEvidence
    ) {
    }
}
