package ar.edu.uade.pfi.backend.dto;

import java.util.Map;

/**
 * EXT-04B5: request body to create a professional-authored evidence region — deliberately has
 * no {@code origin} field at all. {@code origin="professional"} is hardcoded server-side in
 * {@code ProfessionalAnnotationService}; a client can never fabricate {@code origin="ai"}
 * through this endpoint (AI-origin geometry only ever comes from the internal ingestion
 * pipeline, unrelated to this one).
 */
public record CreateProfessionalEvidenceRegionRequestDto(
    String plane,
    String level,
    String structure,
    Integer sliceIndex,
    String geometryType,
    Map<String, Object> geometry,
    String label,
    String note
) {
}
