package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
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
 * Stage 76-C/76-E/76-F: translates a validated {@link AiProductAnalyzeResponseDto} into the
 * structured persistence records, and translates persisted records back into the Frontend-facing
 * {@link AiProductAnalysisResultDto} — the two directions live together so the shape returned by
 * POST and the shape returned by GET are built from the exact same rules.
 *
 * <p>Never reinterprets a value: a {@code null} {@code level} stays {@code null}, a {@code null}
 * boolean from the AI Module is treated as {@code false} (its absence is not evidence of a
 * negative finding, it is simply "not asserted"), and {@code status}/{@code structure} strings are
 * copied verbatim.
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

    public AiProductAnalysisResultDto toResultDto(AiProductAnalysisRun run, List<AiDiscLevelResult> discLevels, List<AiMeasurementResult> measurements) {
        Map<String, List<AiMeasurementResult>> measurementsByLevel = measurements.stream()
            .collect(Collectors.groupingBy(AiMeasurementResult::discLevelResultId));

        List<AiProductAnalysisResultDto.DiscLevelView> discLevelViews = discLevels.stream()
            .sorted(Comparator.comparingInt(AiDiscLevelResult::rankIndex))
            .map(level -> new AiProductAnalysisResultDto.DiscLevelView(
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
                        measurement.plane(),
                        measurement.level(),
                        measurement.structure(),
                        measurement.areaMm2(),
                        measurement.majorAxisMm(),
                        measurement.minorAxisMm(),
                        measurement.reviewRequired()
                    ))
                    .toList()
            ))
            .toList();

        return new AiProductAnalysisResultDto(
            run.id(),
            run.studyId(),
            run.caseId(),
            run.status(),
            run.warnings() == null ? List.of() : run.warnings(),
            discLevelViews,
            new AiProductAnalysisResultDto.ReviewView(run.humanReviewRequired(), run.humanReviewRequired() ? "pending" : "not_required"),
            run.humanReviewRequired(),
            run.notClinicalDiagnosis(),
            run.autonomousDiagnosis(),
            run.createdAt(),
            run.completedAt()
        );
    }
}
