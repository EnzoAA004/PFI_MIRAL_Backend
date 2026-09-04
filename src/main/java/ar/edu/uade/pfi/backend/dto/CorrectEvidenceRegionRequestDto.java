package ar.edu.uade.pfi.backend.dto;

import java.util.Map;

/** EXT-04B5: request body to append a professional correction of an existing region's geometry. */
public record CorrectEvidenceRegionRequestDto(
    String geometryType,
    Map<String, Object> geometry,
    String comment
) {
}
