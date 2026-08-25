package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One persisted execution of {@code POST /v1/product/analyze} (pfi.ai-product-result.v1) against
 * a Study. {@code status} is the AI Module's own {@code completed}/{@code partial}/{@code failed}
 * value, persisted verbatim — never reinterpreted (stage 76-D).
 *
 * <p>No separate execution-lifecycle state (PENDING/RUNNING) is modeled: the AI call is
 * synchronous and a row is only ever created after the call has already returned a validated
 * result (stage 76-K/76-L) — introducing a lifecycle enum for a state nothing ever observes
 * would be complexity without a caller.
 *
 * <p>{@code sagittalInputId}/{@code axialInputId} are the Backend's own opaque input identifiers
 * (already used elsewhere for {@code /inputs} uploads) — never a filesystem path.
 * {@code rawResponseSanitized} is optional trazability only (stage 76-H); the structured rows
 * ({@link AiDiscLevelResult}, {@link AiMeasurementResult}) are always the primary source of truth.
 */
public record AiProductAnalysisRun(
    String id,
    String studyId,
    String caseId,
    String schemaVersion,
    String releaseId,
    String status,
    boolean humanReviewRequired,
    boolean notClinicalDiagnosis,
    boolean autonomousDiagnosis,
    String traceId,
    String sagittalInputId,
    String axialInputId,
    List<String> warnings,
    Map<String, Object> rawResponseSanitized,
    Instant createdAt,
    Instant completedAt
) {
}
