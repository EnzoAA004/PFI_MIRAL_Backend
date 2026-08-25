package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.dto.AiMeasurementCorrectionRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisResultDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisReviewRequestDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stage 78: professional review/correction workflow on top of the stage-76
 * {@code AiProductAnalysisRun}/{@code AiDiscLevelResult}/{@code AiMeasurementResult} persistence.
 *
 * <p>The AI's original output is never rewritten by anything in this class — it only ever writes
 * to the separate stage-78 review/correction tables ({@code domain_ai_analysis_run_reviews},
 * {@code domain_ai_disc_level_reviews}, {@code domain_ai_measurement_corrections}), then re-reads
 * the analysis through {@link AiProductAnalysisPersistenceService#detail} so every response is
 * built from the same single source of truth as a plain GET.
 *
 * <p>No AI call happens anywhere here (78-N) — review/correction is a pure Backend/DB operation.
 */
@Service
public class AiProductAnalysisReviewService {
    public static final Set<String> REVIEW_STATUSES = Set.of("pending", "accepted", "observed", "rejected");
    private static final Set<String> MEASUREMENT_FIELDS = Set.of("areaMm2", "majorAxisMm", "minorAxisMm");
    private static final int MAX_COMMENT_LENGTH = 2000;

    private final StudyRepository repository;
    private final AiProductAnalysisPersistenceService persistenceService;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public AiProductAnalysisReviewService(StudyRepository repository, AiProductAnalysisPersistenceService persistenceService, @Nullable AuditService auditService) {
        this(repository, persistenceService, auditService, Clock.systemUTC());
    }

    AiProductAnalysisReviewService(StudyRepository repository, AiProductAnalysisPersistenceService persistenceService, AuditService auditService, Clock clock) {
        this.repository = repository;
        this.persistenceService = persistenceService;
        this.auditService = auditService;
        this.clock = clock;
    }

    public AiProductAnalysisResultDto reviewRun(String caseId, String analysisId, AiProductAnalysisReviewRequestDto request, String reviewedBy, String traceId) {
        try {
            AiProductAnalysisRun run = persistenceService.resolveAnalysisRun(caseId, analysisId);
            String status = normalizeStatus(request == null ? null : request.status());
            String comment = sanitizeComment(request == null ? null : request.comment());
            Instant reviewedAt = "pending".equals(status) ? null : clock.instant();
            repository.upsertAiProductAnalysisRunReview(run.id(), status, comment, reviewedBy, reviewedAt);
            audit("ai_product_review.run_status_changed", run.id(), reviewedBy, traceId, Map.of("status", status));
            return persistenceService.detail(caseId, analysisId);
        } catch (IllegalStateException ex) {
            throw new DatabaseUnavailableException("Base de datos no disponible para guardar la revision.");
        }
    }

    public AiProductAnalysisResultDto reviewDiscLevel(String caseId, String analysisId, String discLevelId, AiProductAnalysisReviewRequestDto request, String reviewedBy, String traceId) {
        try {
            AiProductAnalysisRun run = persistenceService.resolveAnalysisRun(caseId, analysisId);
            AiDiscLevelResult discLevel = findDiscLevel(run.id(), discLevelId);
            String status = normalizeStatus(request == null ? null : request.status());
            String comment = sanitizeComment(request == null ? null : request.comment());
            Instant reviewedAt = "pending".equals(status) ? null : clock.instant();
            repository.upsertAiDiscLevelReview(discLevel.id(), status, comment, reviewedBy, reviewedAt);
            audit("ai_product_review.disc_level_status_changed", run.id(), reviewedBy, traceId, Map.of("status", status, "discLevelId", discLevel.id()));
            return persistenceService.detail(caseId, analysisId);
        } catch (IllegalStateException ex) {
            throw new DatabaseUnavailableException("Base de datos no disponible para guardar la revision.");
        }
    }

    public AiProductAnalysisResultDto correctMeasurement(String caseId, String analysisId, String measurementId, AiMeasurementCorrectionRequestDto request, String reviewedBy, String traceId) {
        try {
            AiProductAnalysisRun run = persistenceService.resolveAnalysisRun(caseId, analysisId);
            AiMeasurementResult measurement = findMeasurement(run.id(), measurementId);
            String field = normalizeField(request == null ? null : request.field());
            double correctedValue = validateCorrectedValue(request == null ? null : request.correctedValue());
            String comment = sanitizeComment(request == null ? null : request.comment());
            AiMeasurementCorrection correction = new AiMeasurementCorrection(
                UUID.randomUUID().toString(),
                measurement.id(),
                run.id(),
                field,
                originalValueFor(measurement, field),
                correctedValue,
                unitFor(field),
                comment,
                reviewedBy,
                clock.instant()
            );
            repository.saveAiMeasurementCorrection(correction);
            audit("ai_product_review.measurement_corrected", run.id(), reviewedBy, traceId, Map.of("measurementId", measurement.id(), "field", field));
            return persistenceService.detail(caseId, analysisId);
        } catch (IllegalStateException ex) {
            throw new DatabaseUnavailableException("Base de datos no disponible para guardar la correccion.");
        }
    }

    private AiDiscLevelResult findDiscLevel(String analysisRunId, String discLevelId) {
        return repository.findAiDiscLevelResultsByAnalysisRunId(analysisRunId).stream()
            .filter(candidate -> candidate.id().equals(discLevelId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nivel discal no encontrado."));
    }

    private AiMeasurementResult findMeasurement(String analysisRunId, String measurementId) {
        return repository.findAiMeasurementResultsByAnalysisRunId(analysisRunId).stream()
            .filter(candidate -> candidate.id().equals(measurementId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Medicion no encontrada."));
    }

    private String normalizeStatus(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if (!REVIEW_STATUSES.contains(normalized)) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "AI_PRODUCT_REVIEW_INVALID_STATUS", "El estado de revision no es valido.");
        }
        return normalized;
    }

    /** Optional, plain text, length-capped, and rejected outright if it looks like markup (78: "no permitir XSS persistente"). */
    private String sanitizeComment(String comment) {
        if (comment == null) return null;
        String trimmed = comment.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > MAX_COMMENT_LENGTH || trimmed.contains("<") || trimmed.contains(">")) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "AI_PRODUCT_REVIEW_COMMENT_INVALID", "El comentario de revision no es valido.");
        }
        return trimmed;
    }

    private String normalizeField(String field) {
        if (field == null || !MEASUREMENT_FIELDS.contains(field)) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "AI_PRODUCT_CORRECTION_INVALID_VALUE", "El campo a corregir no es valido.");
        }
        return field;
    }

    /** Rejects null, NaN, and Infinity (78: "value no NaN, value no Infinity"). */
    private double validateCorrectedValue(Double value) {
        if (value == null || !Double.isFinite(value)) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "AI_PRODUCT_CORRECTION_INVALID_VALUE", "El valor de la correccion no es valido.");
        }
        return value;
    }

    private Double originalValueFor(AiMeasurementResult measurement, String field) {
        return switch (field) {
            case "areaMm2" -> measurement.areaMm2();
            case "majorAxisMm" -> measurement.majorAxisMm();
            case "minorAxisMm" -> measurement.minorAxisMm();
            default -> throw new IllegalStateException("unreachable: field already validated");
        };
    }

    /** Fixed per field, never accepted from the client — a correction can never relabel a value's unit. */
    private String unitFor(String field) {
        return "areaMm2".equals(field) ? "mm2" : "mm";
    }

    private void audit(String action, String entityId, String actor, String traceId, Map<String, Object> metadata) {
        if (auditService == null) return;
        try {
            auditService.record(actor == null || actor.isBlank() ? "system" : actor, action, entityId, traceId == null || traceId.isBlank() ? "unavailable" : traceId, metadata);
        } catch (RuntimeException ignored) {
            // Review/correction must not fail just because audit persistence is unavailable.
        }
    }
}
