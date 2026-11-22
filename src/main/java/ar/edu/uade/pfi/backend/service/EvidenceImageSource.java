package ar.edu.uade.pfi.backend.service;

import java.util.Optional;

/**
 * EXT-04B4: el único punto de unión que usa el renderer del PDF para obtener imágenes base de evidencia — nunca un
 * path del filesystem, nunca una llamada HTTP de vuelta a este mismo Backend (`AiBackendEvidenceImageSource`
 * llama a {@link AiBackendService} directamente, en el mismo proceso). Ambos métodos devuelven {@link Optional#empty()}
 * — nunca lanzan excepción — ante cualquier cosa que no sea una imagen genuina y decodificable: un slice no disponible, un
 * asset 404, un módulo de IA inalcanzable o una respuesta malformada son todos simplemente "sin imagen", de modo que una
 * imagen no esencial faltante nunca se convierta en un 500 para todo el PDF (precedente 89-CONT/EXT-04B3.5).
 */
public interface EvidenceImageSource {
    /** Replica {@code GET /api/ai/series/{inputId}/slices/{index}} (el `baseImage` propio de EXT-04B3.5). */
    Optional<byte[]> fetchSeriesSlice(String inputId, int sliceIndex);

    /** Replica {@code GET /api/ai/assets/{runId}/{plane}/{assetName}} (el asset propio de un `MASK_REFERENCE`). */
    Optional<byte[]> fetchAsset(String runId, String plane, String assetName);
}
