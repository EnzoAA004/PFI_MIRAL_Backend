package ar.edu.uade.pfi.backend.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Expected identity of the frozen POST /v1/product/analyze contract (pfi.ai-product-result.v1,
 * releaseId post-e50-ai-product-freeze-v1 — see docs/contracts/ai-product-result-v1.schema.json
 * in the AI Module repo). Centralized here instead of a literal string scattered across classes.
 */
@Validated
@ConfigurationProperties(prefix = "pfi.ai-product-contract")
public record AiProductContractProperties(@NotBlank String expectedSchemaVersion, @NotBlank String expectedReleaseId) {
    public static AiProductContractProperties defaults() {
        return new AiProductContractProperties("pfi.ai-product-result.v1", "post-e50-ai-product-freeze-v1");
    }
}
