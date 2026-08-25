package ar.edu.uade.pfi.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Deserialization-level tests for the pfi.ai-product-result.v1 response contract (stage 75-C).
 * No Spring context needed — a plain ObjectMapper is exactly what AiServiceClient's WebClient
 * codec uses under the hood.
 */
class AiProductAnalyzeResponseDtoTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Test 4/5/6: level=null (abstained/unavailable) deserializes cleanly — never an NPE, never coerced to a placeholder. */
    @Test
    void nullLevelDeserializesWithoutExceptionAndIsNeverCoerced() throws Exception {
        String json = """
            {
              "schemaVersion": "pfi.ai-product-result.v1",
              "runId": "product-abc123",
              "traceId": "trace-1",
              "caseId": "case-1",
              "release": {"releaseId": "post-e50-ai-product-freeze-v1"},
              "status": "partial",
              "planes": {"sagittal": {"status": "unavailable", "runtimeAvailable": false, "reviewRequired": true}, "axial": {"status": "not_requested", "runtimeAvailable": false, "reviewRequired": true}},
              "discLevels": [
                {
                  "level": null,
                  "localization": {"status": "unavailable", "validated": true, "source": "67B2", "abstentionPreserved": false, "warnings": ["67B2_RUNTIME_PARITY_NOT_PROVEN"]},
                  "axialPairing": {"status": "not_requested", "validated": false, "experimental": true, "reviewRequired": true},
                  "measurements": [],
                  "degenerativeFindings": {"status": "blocked_preprocessing_parity", "automaticInferenceEnabled": false, "reviewRequired": true}
                }
              ],
              "warnings": ["67B2_RUNTIME_PARITY_NOT_PROVEN"],
              "review": {"required": true, "status": "pending"},
              "humanReviewRequired": true,
              "autonomousDiagnosis": false,
              "notClinicalDiagnosis": true
            }
            """;

        AiProductAnalyzeResponseDto response = objectMapper.readValue(json, AiProductAnalyzeResponseDto.class);

        assertEquals(1, response.discLevels().size());
        assertNull(response.discLevels().get(0).level(), "level must stay null, never fabricated");
        assertEquals("unavailable", response.discLevels().get(0).localization().status());
    }

    /** Test 7/8: 67C validated=false and experimental=true survive deserialization unchanged. */
    @Test
    void axialPairingValidatedFalseAndExperimentalTruePreserved() throws Exception {
        AiProductAnalyzeResponseDto.AxialPairing pairing = objectMapper.readValue(
            """
            {"status": "experimental", "validated": false, "experimental": true, "reviewRequired": true, "source": "67C"}
            """,
            AiProductAnalyzeResponseDto.AxialPairing.class
        );

        assertFalse(pairing.validated());
        assertTrue(pairing.experimental());
        assertEquals("67C", pairing.source());
    }

    /** Test 9: P10.7's blocked_preprocessing_parity deserializes as exactly that status string — nothing here converts it to a negative finding. */
    @Test
    void pathologyBlockedStatusIsPreservedVerbatimNeverInterpreted() throws Exception {
        AiProductAnalyzeResponseDto.DegenerativeFindings findings = objectMapper.readValue(
            """
            {"status": "blocked_preprocessing_parity", "automaticInferenceEnabled": false, "checkpointSha256": "abc", "reviewRequired": true}
            """,
            AiProductAnalyzeResponseDto.DegenerativeFindings.class
        );

        assertEquals("blocked_preprocessing_parity", findings.status());
        assertFalse(findings.automaticInferenceEnabled());
    }

    /** Test 19: an unknown top-level field from a future AI Module release must not break deserialization. */
    @Test
    void unknownTopLevelFieldIsIgnoredForForwardCompatibility() throws Exception {
        String json = """
            {
              "schemaVersion": "pfi.ai-product-result.v1",
              "runId": "product-abc123",
              "caseId": "case-1",
              "release": {"releaseId": "post-e50-ai-product-freeze-v1"},
              "status": "completed",
              "planes": {"sagittal": {"status": "available", "runtimeAvailable": true, "reviewRequired": true}, "axial": {"status": "not_requested", "runtimeAvailable": false, "reviewRequired": true}},
              "discLevels": [],
              "warnings": [],
              "review": {"required": true, "status": "pending"},
              "humanReviewRequired": true,
              "autonomousDiagnosis": false,
              "notClinicalDiagnosis": true,
              "aFieldThisDtoDoesNotKnowAboutYet": {"nested": true}
            }
            """;

        AiProductAnalyzeResponseDto response = objectMapper.readValue(json, AiProductAnalyzeResponseDto.class);

        assertEquals("completed", response.status());
    }

    /** Test 20: warnings array is preserved as-is. */
    @Test
    void warningsArePreserved() throws Exception {
        String json = """
            {
              "schemaVersion": "pfi.ai-product-result.v1",
              "runId": "product-abc123",
              "caseId": "case-1",
              "release": {"releaseId": "post-e50-ai-product-freeze-v1"},
              "status": "partial",
              "planes": {"sagittal": {"status": "unavailable", "runtimeAvailable": false, "reviewRequired": true}, "axial": {"status": "not_requested", "runtimeAvailable": false, "reviewRequired": true}},
              "discLevels": [],
              "warnings": ["67B2_RUNTIME_PARITY_NOT_PROVEN", "sagittal_input_not_requested"],
              "review": {"required": true, "status": "pending"},
              "humanReviewRequired": true,
              "autonomousDiagnosis": false,
              "notClinicalDiagnosis": true
            }
            """;

        AiProductAnalyzeResponseDto response = objectMapper.readValue(json, AiProductAnalyzeResponseDto.class);

        assertEquals(2, response.warnings().size());
        assertTrue(response.warnings().contains("67B2_RUNTIME_PARITY_NOT_PROVEN"));
    }
}
