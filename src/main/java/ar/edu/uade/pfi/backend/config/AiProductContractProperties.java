package ar.edu.uade.pfi.backend.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Identidad esperada del contrato congelado POST /v1/product/analyze (pfi.ai-product-result.v1,
 * releaseId post-e50-ai-product-freeze-v1 — ver docs/contracts/ai-product-result-v1.schema.json
 * en el repo del módulo de IA). Centralizada acá en lugar de un string literal desperdigado entre clases.
 */
@Validated
@ConfigurationProperties(prefix = "pfi.ai-product-contract")
public record AiProductContractProperties(@NotBlank String expectedSchemaVersion, @NotBlank String expectedReleaseId) {
    public static AiProductContractProperties defaults() {
        return new AiProductContractProperties("pfi.ai-product-result.v1", "post-e50-ai-product-freeze-v1");
    }
}
