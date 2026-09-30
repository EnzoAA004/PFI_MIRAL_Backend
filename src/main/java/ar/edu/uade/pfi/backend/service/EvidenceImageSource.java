package ar.edu.uade.pfi.backend.service;

import java.util.Optional;

/**
 * EXT-04B4: the single seam the PDF renderer uses to fetch evidence base images — never a
 * filesystem path, never an HTTP call back into this same Backend (`AiBackendEvidenceImageSource`
 * calls {@link AiBackendService} directly, in-process). Both methods return {@link Optional#empty()}
 * — never throw — for anything short of a genuine, decodable image: an unavailable slice, a 404
 * asset, an unreachable AI Module, or a malformed response are all just "no image", so a missing
 * non-essential picture never turns into a 500 for the whole PDF (89-CONT/EXT-04B3.5 precedent).
 */
public interface EvidenceImageSource {
    /** Mirrors {@code GET /api/ai/series/{inputId}/slices/{index}} (EXT-04B3.5's own `baseImage`). */
    Optional<byte[]> fetchSeriesSlice(String inputId, int sliceIndex);

    /** Mirrors {@code GET /api/ai/assets/{runId}/{plane}/{assetName}} (a `MASK_REFERENCE`'s own asset). */
    Optional<byte[]> fetchAsset(String runId, String plane, String assetName);
}
