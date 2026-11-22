package ar.edu.uade.pfi.backend.dto;

import java.util.Map;

/**
 * EXT-04B5: body del request para crear una región de evidencia de autoría profesional — deliberadamente no tiene
 * ningún campo {@code origin}. {@code origin="professional"} está hardcodeado del lado del servidor en
 * {@code ProfessionalAnnotationService}; un cliente nunca puede fabricar {@code origin="ai"}
 * a través de este endpoint (la geometría de origen IA solo proviene del pipeline interno de
 * ingesta, que no tiene relación con este).
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
