package ar.edu.uade.pfi.backend.dto;

import ar.edu.uade.pfi.backend.domain.AiEvidenceRegionCorrection;
import java.time.Instant;
import java.util.Map;

/** EXT-04B5: public shape of a just-appended correction — {@code reviewedBy} is never included. */
public record EvidenceRegionCorrectionResponseDto(
    String correctionId,
    String evidenceRegionId,
    String analysisRunId,
    String originalGeometryType,
    Map<String, Object> originalGeometry,
    String correctedGeometryType,
    Map<String, Object> correctedGeometry,
    String comment,
    Instant createdAt
) {
    public static EvidenceRegionCorrectionResponseDto from(AiEvidenceRegionCorrection correction) {
        return new EvidenceRegionCorrectionResponseDto(
            correction.id(), correction.evidenceRegionId(), correction.analysisRunId(),
            correction.originalGeometryType(), correction.originalGeometry(),
            correction.correctedGeometryType(), correction.correctedGeometry(),
            correction.comment(), correction.createdAt()
        );
    }
}
