package ar.edu.uade.pfi.backend.dto;

import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import java.time.Instant;
import java.util.Map;

/**
 * EXT-04B5: public shape of a just-created/corrected {@link AiEvidenceRegion} — omits
 * {@code authoredBy}/{@code comment}'s reviewer identity is never in this record to begin with
 * (only {@code authoredBy} is, and it is deliberately excluded here), same rule EXT-04A/EXT-04B2
 * already apply to reviewer identity elsewhere in this product.
 */
public record EvidenceRegionResponseDto(
    String evidenceId,
    String analysisRunId,
    String plane,
    String level,
    String structure,
    Integer sliceIndex,
    String origin,
    String geometryType,
    Map<String, Object> geometry,
    String label,
    boolean experimental,
    Instant createdAt
) {
    public static EvidenceRegionResponseDto from(AiEvidenceRegion region) {
        return new EvidenceRegionResponseDto(
            region.id(), region.analysisRunId(), region.plane(), region.level(), region.structure(),
            region.sliceIndex(), region.origin(), region.geometryType(), region.geometry(),
            region.label(), region.experimental(), region.createdAt()
        );
    }
}
