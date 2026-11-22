package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Contrato de respuesta para POST /v1/product/analyze — pfi.ai-product-result.v1, release
 * post-e50-ai-product-freeze-v1. Modelado directamente a partir de
 * docs/contracts/ai-product-result-v1.schema.json en el repo del módulo de IA (la fuente de verdad),
 * no inventado del lado del Backend.
 *
 * <p>Todo tipo acá es {@code ignoreUnknown = true}: se permite que el contrato del módulo de IA agregue
 * campos nuevos (los objetos anidados del schema no declaran {@code additionalProperties: false|
 * true}), y este Backend debe seguir funcionando cuando lo haga, sin un re-deploy solo para agregar un
 * getter. Los campos que el schema declara como nullable (sobre todo {@link DiscLevel#level()}) son
 * campos Java nullable simples acá — nunca con valor por defecto, nunca forzados a un string placeholder.
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
     * Un nivel de disco en la secuencia absoluta L1-L2..L5-S1. {@code level} admite null por
     * contrato: {@code null} es la forma válida y esperada cuando {@link Localization#status()} es
     * {@code abstained} o {@code unavailable} — nunca lo reemplaces por un placeholder.
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

    /** {@code status}: {@code localized | abstained | adapter_pending | unavailable | not_requested}. Fuente: 67B2. */
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

    /** {@code status}: {@code paired | adapter_pending | experimental | unavailable | rejected | not_requested}. Fuente: 67C. */
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

    /** {@code status} es actualmente siempre {@code blocked_preprocessing_parity} — nunca un hallazgo negativo. Ver las notas de manejo de P10.7. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DegenerativeFindings(String status, Boolean automaticInferenceEnabled, String checkpointSha256, Boolean reviewRequired) {
    }

    /** {@code status}: {@code pending | in_review | completed}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Review(Boolean required, String status, Boolean professionalReviewRequired) {
    }
}
