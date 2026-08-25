package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Public request body for {@code POST /api/studies/{caseId}/product-analysis}. {@code caseId}
 * comes from the path, not the body — this is a sub-resource of a study, matching the
 * {@code /api/studies/{caseId}/...} convention already used elsewhere in this controller family.
 *
 * <p>Stage 76: {@code sagittalInputId}/{@code axialInputId} are deprecated and ignored. The
 * Backend now resolves both from the Study's own registered inputs via
 * {@code AiStudyInputResolver} — the Frontend only needs to send {@code caseId} (path) and
 * optional {@code metadata}. The fields are kept on the wire, rather than removed outright, only
 * so an existing caller that still sends them does not fail deserialization; they carry no effect.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiProductAnalysisApiRequestDto(
    @Deprecated(forRemoval = true) String sagittalInputId,
    @Deprecated(forRemoval = true) String axialInputId,
    Map<String, Object> metadata
) {
}
