package ar.edu.uade.pfi.backend.dto;

import ar.edu.uade.pfi.backend.domain.ProfessionalFeedbackCandidate;
import java.time.Instant;
import java.util.Map;

/**
 * EXT-07D/EXT-05: la forma pública de un {@link ProfessionalFeedbackCandidate} — deliberadamente
 * omite {@code reviewedBy} Y {@code curatorId} (la misma regla que EXT-04A ya aplica a la identidad del
 * revisor en el reporte estructurado: solo interna, verificable en la capa de base de datos, nunca
 * serializada a una respuesta de la API).
 */
public record ProfessionalFeedbackCandidateDto(
    String id,
    String caseId,
    String analysisRunId,
    String source,
    String correctionId,
    String level,
    String plane,
    String structure,
    String metric,
    String geometryType,
    Double aiOriginalValue,
    Map<String, Object> aiOriginalGeometry,
    Double professionalValue,
    Map<String, Object> professionalGeometry,
    String modelId,
    String modelSha256,
    String pipelineVersion,
    String note,
    String status,
    Instant correctedAt,
    Instant capturedAt,
    Instant curatedAt,
    String exclusionReason
) {
    public static ProfessionalFeedbackCandidateDto from(ProfessionalFeedbackCandidate candidate) {
        return new ProfessionalFeedbackCandidateDto(
            candidate.id(),
            candidate.caseId(),
            candidate.analysisRunId(),
            candidate.source(),
            candidate.correctionId(),
            candidate.level(),
            candidate.plane(),
            candidate.structure(),
            candidate.metric(),
            candidate.geometryType(),
            candidate.aiOriginalValue(),
            candidate.aiOriginalGeometry(),
            candidate.professionalValue(),
            candidate.professionalGeometry(),
            candidate.modelId(),
            candidate.modelSha256(),
            candidate.pipelineVersion(),
            candidate.note(),
            candidate.status(),
            candidate.correctedAt(),
            candidate.capturedAt(),
            candidate.curatedAt(),
            candidate.exclusionReason()
        );
    }
}
