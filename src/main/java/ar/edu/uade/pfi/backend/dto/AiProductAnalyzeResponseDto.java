package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Response contract for POST /v1/product/analyze — pfi.ai-product-result.v1, release
 * post-e50-ai-product-freeze-v1. Modeled directly from
 * docs/contracts/ai-product-result-v1.schema.json in the AI Module repo (the source of truth),
 * not invented on the Backend side.
 *
 * <p>Every type here is {@code ignoreUnknown = true}: the AI Module's contract is allowed to grow
 * new fields (nested objects in the schema do not declare {@code additionalProperties: false|
 * true}), and this Backend must keep working when it does, without a re-deploy just to add a
 * getter. Fields the schema declares as nullable (most notably {@link DiscLevel#level()}) are
 * plain nullable Java fields here — never defaulted, never coerced to a placeholder string.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiProductAnalyzeResponseDto(
    String schemaVersion,
    String runId,
    String traceId,
    String caseId,
    Release release,
    String status,
    Planes planes,
    List<DiscLevel> discLevels,
    List<String> warnings,
    Review review,
    Boolean notClinicalDiagnosis,
    Boolean autonomousDiagnosis,
    Boolean humanReviewRequired
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Release(String releaseId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Planes(Plane sagittal, Plane axial) {
    }

    /** {@code status}: {@code available | unavailable | not_requested}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Plane(
        String status,
        Boolean runtimeAvailable,
        String inputId,
        String runId,
        Boolean reviewRequired,
        Segmentation segmentation,
        Morphometry morphometry
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segmentation(String status, Boolean validated, Boolean reviewRequired, List<String> maskClasses) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Morphometry(
        String status,
        Boolean validated,
        Boolean descriptiveOnly,
        Boolean reviewRequired,
        Integer measurementCount
    ) {
    }

    /**
     * One disc level in the absolute L1-L2..L5-S1 sequence. {@code level} is nullable by
     * contract: {@code null} is the valid, expected shape when {@link Localization#status()} is
     * {@code abstained} or {@code unavailable} — never replace it with a placeholder.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DiscLevel(
        String level,
        Localization localization,
        AxialPairing axialPairing,
        List<Measurement> measurements,
        DegenerativeFindings degenerativeFindings
    ) {
    }

    /** {@code status}: {@code localized | abstained | adapter_pending | unavailable | not_requested}. Source: 67B2. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Localization(
        String status,
        Boolean validated,
        String source,
        Boolean abstentionPreserved,
        Boolean adapterPending,
        List<String> warnings
    ) {
    }

    /** {@code status}: {@code paired | adapter_pending | experimental | unavailable | rejected | not_requested}. Source: 67C. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AxialPairing(
        String status,
        Boolean validated,
        Boolean experimental,
        Boolean reviewRequired,
        Boolean adapterPending,
        String source,
        List<String> warnings
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Measurement(
        String plane,
        String level,
        String structure,
        Double areaMm2,
        Double majorAxisMm,
        Double minorAxisMm,
        Boolean reviewRequired
    ) {
    }

    /** {@code status} is currently always {@code blocked_preprocessing_parity} — never a negative finding. See P10.7 handling notes. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DegenerativeFindings(String status, Boolean automaticInferenceEnabled, String checkpointSha256, Boolean reviewRequired) {
    }

    /** {@code status}: {@code pending | in_review | completed}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Review(Boolean required, String status, Boolean professionalReviewRequired) {
    }
}
