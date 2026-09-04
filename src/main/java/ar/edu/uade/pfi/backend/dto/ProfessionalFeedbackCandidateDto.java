package ar.edu.uade.pfi.backend.dto;

import ar.edu.uade.pfi.backend.domain.ProfessionalFeedbackCandidate;
import java.time.Instant;
import java.util.Map;

/**
 * EXT-07D/EXT-05: the public shape of a {@link ProfessionalFeedbackCandidate} — deliberately
 * omits {@code reviewedBy} AND {@code curatorId} (same rule EXT-04A already applies to reviewer
 * identity in the structured report: internal-only, verifiable at the database layer, never
 * serialized to an API response).
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
