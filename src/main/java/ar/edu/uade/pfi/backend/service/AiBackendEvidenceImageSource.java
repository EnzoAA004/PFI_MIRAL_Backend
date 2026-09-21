package ar.edu.uade.pfi.backend.service;

import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * EXT-04B4: production {@link EvidenceImageSource} — delegates to the already-existing,
 * already-tested {@link AiBackendService} in-process (a plain Java method call, never an HTTP
 * round-trip back into this same Backend). Any failure (bad status, empty body, upstream
 * unreachable, invalid identifier) is swallowed into {@link Optional#empty()} here — the PDF
 * renderer's only job with that is to fall back to text, never to propagate a 500 for a
 * non-essential image (Phase 12).
 */
@Component
public class AiBackendEvidenceImageSource implements EvidenceImageSource {
    private final AiBackendService aiBackendService;

    public AiBackendEvidenceImageSource(AiBackendService aiBackendService) {
        this.aiBackendService = aiBackendService;
    }

    @Override
    public Optional<byte[]> fetchSeriesSlice(String inputId, int sliceIndex) {
        try {
            return usableBody(aiBackendService.getSeriesSlice(inputId, sliceIndex));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<byte[]> fetchAsset(String runId, String plane, String assetName) {
        try {
            return usableBody(aiBackendService.getAsset(runId, plane, assetName));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private Optional<byte[]> usableBody(ResponseEntity<byte[]> response) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) return Optional.empty();
        byte[] body = response.getBody();
        return body == null || body.length == 0 ? Optional.empty() : Optional.of(body);
    }
}
