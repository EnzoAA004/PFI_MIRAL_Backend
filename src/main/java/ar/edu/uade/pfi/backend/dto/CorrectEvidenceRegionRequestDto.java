package ar.edu.uade.pfi.backend.dto;

import java.util.Map;

/** EXT-04B5: body del request para agregar una corrección profesional a la geometría de una región existente. */
public record CorrectEvidenceRegionRequestDto(
    String geometryType,
    Map<String, Object> geometry,
    String comment
) {
}
