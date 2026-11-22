package ar.edu.uade.pfi.backend.service;

import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * EXT-04B4: {@link EvidenceImageSource} de producción — delega en el {@link AiBackendService} ya existente y
 * ya testeado dentro del mismo proceso (una llamada a método Java simple, nunca un round-trip
 * HTTP de vuelta a este mismo Backend). Cualquier falla (status inválido, body vacío, upstream
 * inalcanzable, identificador inválido) se traga a {@link Optional#empty()} acá — lo único que
 * tiene que hacer el renderer del PDF con eso es recurrir al texto, nunca propagar un 500 por una
 * imagen no esencial (Fase 12).
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
