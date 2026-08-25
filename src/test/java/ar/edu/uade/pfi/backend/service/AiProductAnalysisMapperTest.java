package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisResultDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Stage 76-C/76-E/76-F: response-to-domain and domain-to-response mapping, no reinterpretation. */
class AiProductAnalysisMapperTest {
    private final AiProductAnalysisMapper mapper = new AiProductAnalysisMapper();

    private AiProductAnalyzeResponseDto response(String status, List<AiProductAnalyzeResponseDto.DiscLevel> discLevels, List<String> warnings) {
        return new AiProductAnalyzeResponseDto(
            "pfi.ai-product-result.v1",
            "product-1",
            "trace-1",
            "case-1",
            new AiProductAnalyzeResponseDto.Release("post-e50-ai-product-freeze-v1"),
            status,
            null,
            discLevels,
            warnings,
            new AiProductAnalyzeResponseDto.Review(true, "pending", true),
            true,
            false,
            true
        );
    }

    @Test
    void mapsCompletedStatusVerbatim() {
        AiProductAnalysisMapper.Mapped mapped = mapper.toDomain("study-1", response("completed", List.of(), List.of()), "sag-1", "ax-1");

        assertEquals("completed", mapped.run().status());
    }

    @Test
    void mapsPartialStatusVerbatim() {
        AiProductAnalysisMapper.Mapped mapped = mapper.toDomain("study-1", response("partial", List.of(), List.of()), "sag-1", null);

        assertEquals("partial", mapped.run().status());
    }

    @Test
    void mapsFailedStatusVerbatim() {
        AiProductAnalysisMapper.Mapped mapped = mapper.toDomain("study-1", response("failed", List.of(), List.of()), null, null);

        assertEquals("failed", mapped.run().status());
    }

    @Test
    void nullLevelPreservedNotCoerced() {
        AiProductAnalyzeResponseDto.DiscLevel abstained = new AiProductAnalyzeResponseDto.DiscLevel(
            null,
            new AiProductAnalyzeResponseDto.Localization("abstained", true, "67B2", true, false, List.of()),
            new AiProductAnalyzeResponseDto.AxialPairing("not_requested", false, false, false, false, "67C", List.of()),
            List.of(),
            null
        );
        AiProductAnalysisMapper.Mapped mapped = mapper.toDomain("study-1", response("partial", List.of(abstained), List.of()), "sag-1", null);

        assertNull(mapped.discLevels().get(0).level());
        assertEquals("abstained", mapped.discLevels().get(0).localizationStatus());
        assertTrue(mapped.discLevels().get(0).abstentionPreserved());
    }

    @Test
    void rankIndexPreservesOriginalOrderForMultipleNullLevelEntries() {
        AiProductAnalyzeResponseDto.DiscLevel first = discLevel(null, "abstained");
        AiProductAnalyzeResponseDto.DiscLevel second = discLevel(null, "unavailable");
        AiProductAnalysisMapper.Mapped mapped = mapper.toDomain("study-1", response("partial", List.of(first, second), List.of()), "sag-1", null);

        assertEquals(0, mapped.discLevels().get(0).rankIndex());
        assertEquals(1, mapped.discLevels().get(1).rankIndex());
        assertEquals("abstained", mapped.discLevels().get(0).localizationStatus());
        assertEquals("unavailable", mapped.discLevels().get(1).localizationStatus());
    }

    @Test
    void warningsMappedVerbatim() {
        AiProductAnalysisMapper.Mapped mapped = mapper.toDomain("study-1", response("partial", List.of(), List.of("67B2_RUNTIME_PARITY_NOT_PROVEN", "67C_RUNTIME_PARITY_NOT_PROVEN")), "sag-1", null);

        assertEquals(List.of("67B2_RUNTIME_PARITY_NOT_PROVEN", "67C_RUNTIME_PARITY_NOT_PROVEN"), mapped.run().warnings());
    }

    @Test
    void measurementsMappedWithAnalysisRunAndDiscLevelIds() {
        AiProductAnalyzeResponseDto.Measurement measurement = new AiProductAnalyzeResponseDto.Measurement("sagittal", "L4-L5", "disc", 120.5, 12.1, 8.4, true);
        AiProductAnalyzeResponseDto.DiscLevel level = new AiProductAnalyzeResponseDto.DiscLevel(
            "L4-L5",
            new AiProductAnalyzeResponseDto.Localization("localized", true, "67B2", false, false, List.of()),
            new AiProductAnalyzeResponseDto.AxialPairing("paired", true, false, false, false, "67C", List.of()),
            List.of(measurement),
            null
        );
        AiProductAnalysisMapper.Mapped mapped = mapper.toDomain("study-1", response("completed", List.of(level), List.of()), "sag-1", "ax-1");

        AiMeasurementResult persisted = mapped.measurements().get(0);
        assertEquals(mapped.run().id(), persisted.analysisRunId());
        assertEquals(mapped.discLevels().get(0).id(), persisted.discLevelResultId());
        assertEquals("disc", persisted.structure());
        assertEquals(120.5, persisted.areaMm2());
    }

    @Test
    void measurementReviewRequiredDefaultsTrueWhenNull() {
        AiProductAnalyzeResponseDto.Measurement measurement = new AiProductAnalyzeResponseDto.Measurement("sagittal", "L4-L5", "disc", null, null, null, null);
        AiProductAnalyzeResponseDto.DiscLevel level = new AiProductAnalyzeResponseDto.DiscLevel(
            "L4-L5", null, null, List.of(measurement), null
        );
        AiProductAnalysisMapper.Mapped mapped = mapper.toDomain("study-1", response("completed", List.of(level), List.of()), "sag-1", null);

        assertTrue(mapped.measurements().get(0).reviewRequired());
    }

    @Test
    void axialPairingExperimentalAndUnvalidatedPreserved() {
        AiProductAnalyzeResponseDto.DiscLevel level = new AiProductAnalyzeResponseDto.DiscLevel(
            "L4-L5",
            null,
            new AiProductAnalyzeResponseDto.AxialPairing("experimental", false, true, true, false, "67C", List.of()),
            List.of(),
            null
        );
        AiProductAnalysisMapper.Mapped mapped = mapper.toDomain("study-1", response("partial", List.of(level), List.of()), "sag-1", "ax-1");

        AiDiscLevelResult persisted = mapped.discLevels().get(0);
        assertEquals("experimental", persisted.axialPairingStatus());
        assertFalse(persisted.axialPairingValidated());
        assertTrue(persisted.axialPairingExperimental());
        assertTrue(persisted.axialPairingReviewRequired());
    }

    @Test
    void degenerativeFindingsBlockedPreprocessingParityPreserved() {
        AiProductAnalyzeResponseDto.DiscLevel level = new AiProductAnalyzeResponseDto.DiscLevel(
            "L4-L5",
            null,
            null,
            List.of(),
            new AiProductAnalyzeResponseDto.DegenerativeFindings("blocked_preprocessing_parity", false, "sha-1", true)
        );
        AiProductAnalysisMapper.Mapped mapped = mapper.toDomain("study-1", response("partial", List.of(level), List.of()), "sag-1", null);

        assertEquals("blocked_preprocessing_parity", mapped.discLevels().get(0).degenerativeFindingsStatus());
        assertFalse(mapped.discLevels().get(0).automaticInferenceEnabled());
    }

    @Test
    void toResultDtoGroupsMeasurementsByDiscLevelAndSortsByRankIndex() {
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            "run-1", "study-1", "case-1", "pfi.ai-product-result.v1", "post-e50-ai-product-freeze-v1",
            "completed", true, true, false, "trace-1", "sag-1", "ax-1", List.of(), null,
            java.time.Instant.now(), java.time.Instant.now()
        );
        AiDiscLevelResult levelZero = new AiDiscLevelResult("level-0", "run-1", 1, "L5-S1", "localized", "67B2", true, false, "paired", true, false, false, null, false);
        AiDiscLevelResult levelOne = new AiDiscLevelResult("level-1", "run-1", 0, "L4-L5", "localized", "67B2", true, false, "paired", true, false, false, null, false);
        AiMeasurementResult measurement = new AiMeasurementResult("measurement-1", "run-1", "level-1", "sagittal", "L4-L5", "disc", 1.0, 2.0, 3.0, true);

        AiProductAnalysisResultDto dto = mapper.toResultDto(run, List.of(levelZero, levelOne), List.of(measurement));

        assertEquals("L4-L5", dto.discLevels().get(0).level());
        assertEquals("L5-S1", dto.discLevels().get(1).level());
        assertEquals(1, dto.discLevels().get(0).measurements().size());
        assertEquals(0, dto.discLevels().get(1).measurements().size());
    }

    private AiProductAnalyzeResponseDto.DiscLevel discLevel(String level, String localizationStatus) {
        return new AiProductAnalyzeResponseDto.DiscLevel(
            level,
            new AiProductAnalyzeResponseDto.Localization(localizationStatus, false, "67B2", true, false, List.of()),
            null,
            List.of(),
            null
        );
    }
}
