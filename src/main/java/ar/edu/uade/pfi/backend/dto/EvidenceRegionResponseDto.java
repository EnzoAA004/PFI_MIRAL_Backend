package ar.edu.uade.pfi.backend.dto;

import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import java.time.Instant;
import java.util.Map;

/**
 * EXT-04B5: forma pública de un {@link AiEvidenceRegion} recién creado/corregido — omite
 * {@code authoredBy}/la identidad del revisor de {@code comment} nunca está en este record desde un principio
 * (solo está {@code authoredBy}, y se excluye deliberadamente acá), la misma regla que EXT-04A/EXT-04B2
 * ya aplican a la identidad del revisor en el resto de este producto.
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
