package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiDiscLevelReview;
import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRunReview;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisResultDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Etapa 76-C/76-E/76-F, ampliada en la etapa 78: traduce un
 * {@link AiProductAnalyzeResponseDto} validado a los registros de persistencia estructurados, y traduce los
 * registros persistidos — junto con su overlay de revisión profesional (etapa 78) — de vuelta al
 * {@link AiProductAnalysisResultDto} orientado al Frontend.
 *
 * <p>Nunca reinterpreta un valor: un {@code level} {@code null} sigue siendo {@code null}, un
 * boolean {@code null} del módulo de IA se trata como {@code false} (su ausencia no es evidencia de un
 * hallazgo negativo, es simplemente "no afirmado"), y los strings {@code status}/{@code structure} se
 * copian textualmente. El overlay de revisión/corrección de la etapa 78 es puramente aditivo — nunca reescribe
 * un campo de {@link AiDiscLevelResult}/{@link AiMeasurementResult}.
 */
@Component
public class AiProductAnalysisMapper {

    public record Mapped(AiProductAnalysisRun run, List<AiDiscLevelResult> discLevels, List<AiMeasurementResult> measurements) {
    }

    public Mapped toDomain(String studyId, AiProductAnalyzeResponseDto response, String sagittalInputId, String axialInputId) {
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            runId,
            studyId,
            response.caseId(),
            response.schemaVersion(),
            response.release() == null ? null : response.release().releaseId(),
            response.status(),
            Boolean.TRUE.equals(response.humanReviewRequired()),
            Boolean.TRUE.equals(response.notClinicalDiagnosis()),
            Boolean.TRUE.equals(response.autonomousDiagnosis()),
            response.traceId() == null ? "" : response.traceId(),
            sagittalInputId,
            axialInputId,
            response.warnings() == null ? List.of() : List.copyOf(response.warnings()),
            null,
            now,
            now
        );

        List<AiDiscLevelResult> discLevels = new ArrayList<>();
        List<AiMeasurementResult> measurements = new ArrayList<>();
        List<AiProductAnalyzeResponseDto.DiscLevel> sourceLevels = response.discLevels() == null ? List.of() : response.discLevels();
        for (int rankIndex = 0; rankIndex < sourceLevels.size(); rankIndex++) {
            AiProductAnalyzeResponseDto.DiscLevel source = sourceLevels.get(rankIndex);
            String discLevelId = UUID.randomUUID().toString();
            AiProductAnalyzeResponseDto.Localization localization = source.localization();
            AiProductAnalyzeResponseDto.AxialPairing axialPairing = source.axialPairing();
            AiProductAnalyzeResponseDto.DegenerativeFindings degenerative = source.degenerativeFindings();
            discLevels.add(new AiDiscLevelResult(
                discLevelId,
                runId,
                rankIndex,
                source.level(),
                localization == null ? null : localization.status(),
                localization == null ? null : localization.source(),
                localization != null && Boolean.TRUE.equals(localization.validated()),
                localization != null && Boolean.TRUE.equals(localization.abstentionPreserved()),
                axialPairing == null ? null : axialPairing.status(),
                axialPairing != null && Boolean.TRUE.equals(axialPairing.validated()),
                axialPairing != null && Boolean.TRUE.equals(axialPairing.experimental()),
                axialPairing != null && Boolean.TRUE.equals(axialPairing.reviewRequired()),
                degenerative == null ? null : degenerative.status(),
                degenerative != null && Boolean.TRUE.equals(degenerative.automaticInferenceEnabled())
            ));

            List<AiProductAnalyzeResponseDto.Measurement> sourceMeasurements = source.measurements() == null ? List.of() : source.measurements();
            for (AiProductAnalyzeResponseDto.Measurement measurement : sourceMeasurements) {
                measurements.add(new AiMeasurementResult(
                    UUID.randomUUID().toString(),
                    runId,
                    discLevelId,
                    measurement.plane(),
                    measurement.level(),
                    measurement.structure(),
                    measurement.areaMm2(),
                    measurement.majorAxisMm(),
                    measurement.minorAxisMm(),
                    measurement.reviewRequired() == null || measurement.reviewRequired()
                ));
            }
        }
        return new Mapped(run, discLevels, measurements);
    }

    /**
     * Etapa 78: {@code runReview}/{@code discLevelReviews}/{@code corrections} pueden estar vacíos/null
     * (una corrida recién persistida todavía no tiene revisión) — una revisión ausente se renderiza como
     * {@code status="pending"}, nunca como un error ni como una sección omitida.
     */
    public AiProductAnalysisResultDto toResultDto(
        AiProductAnalysisRun run,
        List<AiDiscLevelResult> discLevels,
        List<AiMeasurementResult> measurements,
        AiProductAnalysisRunReview runReview,
        List<AiDiscLevelReview> discLevelReviews,
        List<AiMeasurementCorrection> corrections
    ) {
        Map<String, List<AiMeasurementResult>> measurementsByLevel = measurements.stream()
            .collect(Collectors.groupingBy(AiMeasurementResult::discLevelResultId));
        Map<String, AiDiscLevelReview> discLevelReviewsById = (discLevelReviews == null ? List.<AiDiscLevelReview>of() : discLevelReviews).stream()
            .collect(Collectors.toMap(AiDiscLevelReview::discLevelResultId, review -> review));
        Map<String, List<AiMeasurementCorrection>> correctionsByMeasurement = (corrections == null ? List.<AiMeasurementCorrection>of() : corrections).stream()
            .collect(Collectors.groupingBy(AiMeasurementCorrection::measurementId));

        List<AiProductAnalysisResultDto.DiscLevelView> discLevelViews = discLevels.stream()
            .sorted(Comparator.comparingInt(AiDiscLevelResult::rankIndex))
            .map(level -> {
                AiDiscLevelReview levelReview = discLevelReviewsById.get(level.id());
                return new AiProductAnalysisResultDto.DiscLevelView(
                    level.id(),
                    level.level(),
                    level.localizationStatus(),
                    level.localizationValidated(),
                    level.abstentionPreserved(),
                    level.axialPairingStatus(),
                    level.axialPairingValidated(),
                    level.axialPairingExperimental(),
                    level.axialPairingReviewRequired(),
                    level.degenerativeFindingsStatus(),
                    level.automaticInferenceEnabled(),
                    measurementsByLevel.getOrDefault(level.id(), List.of()).stream()
                        .map(measurement -> new AiProductAnalysisResultDto.MeasurementView(
                            measurement.id(),
                            measurement.plane(),
                            measurement.level(),
                            measurement.structure(),
                            measurement.areaMm2(),
                            measurement.majorAxisMm(),
                            measurement.minorAxisMm(),
                            measurement.reviewRequired(),
                            latestCorrectionsPerField(correctionsByMeasurement.getOrDefault(measurement.id(), List.of()))
                        ))
                        .toList(),
                    reviewView(
                        run.humanReviewRequired(),
                        levelReview == null ? null : levelReview.reviewStatus(),
                        levelReview == null ? null : levelReview.reviewComment(),
                        levelReview == null ? null : levelReview.reviewedBy(),
                        levelReview == null ? null : levelReview.reviewedAt()
                    )
                );
            })
            .toList();

        return new AiProductAnalysisResultDto(
            run.id(),
            run.studyId(),
            run.caseId(),
            run.status(),
            run.warnings() == null ? List.of() : run.warnings(),
            discLevelViews,
            reviewView(
                run.humanReviewRequired(),
                runReview == null ? null : runReview.reviewStatus(),
                runReview == null ? null : runReview.reviewComment(),
                runReview == null ? null : runReview.reviewedBy(),
                runReview == null ? null : runReview.reviewedAt()
            ),
            run.humanReviewRequired(),
            run.notClinicalDiagnosis(),
            run.autonomousDiagnosis(),
            run.createdAt(),
            run.completedAt()
        );
    }

    private AiProductAnalysisResultDto.ReviewView reviewView(boolean required, String status, String comment, String reviewedBy, Instant reviewedAt) {
        return new AiProductAnalysisResultDto.ReviewView(required, status == null ? "pending" : status, comment, reviewedBy, reviewedAt);
    }

    /** Una corrección por {@code field} — la última por {@code createdAt} cuando un campo se corrigió más de una vez. */
    private List<AiProductAnalysisResultDto.MeasurementCorrectionView> latestCorrectionsPerField(List<AiMeasurementCorrection> measurementCorrections) {
        return measurementCorrections.stream()
            .collect(Collectors.groupingBy(AiMeasurementCorrection::field))
            .values().stream()
            .map(sameFieldCorrections -> sameFieldCorrections.stream().max(Comparator.comparing(AiMeasurementCorrection::createdAt)).orElseThrow())
            .map(correction -> new AiProductAnalysisResultDto.MeasurementCorrectionView(
                correction.field(),
                correction.originalValue(),
                correction.correctedValue(),
                correction.unit(),
                correction.comment(),
                correction.reviewedBy(),
                correction.createdAt()
            ))
            .toList();
    }
}
