package ar.edu.uade.pfi.backend.service;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelReview;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegionCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.ProfessionalFeedbackCandidate;
import ar.edu.uade.pfi.backend.domain.ProfessionalFeedbackStatusChange;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * EXT-07D: solo CAPTURA — nunca entrenamiento, nunca aprendizaje online/activo, nunca un cambio de
 * checkpoint en runtime. Este servicio lee las correcciones profesionales y los hallazgos de autoría profesional
 * que ya existen (append-only, producidos por los flujos de revisión/corrección que este producto
 * tiene desde la Etapa 78/EXT-04B2) y captura una fila estructurada y trazable de
 * {@link ProfessionalFeedbackCandidate} por cada una que todavía no haya capturado.
 *
 * <p>Idempotente: capturar la misma corrección/nuevo hallazgo dos veces es un no-op (se chequea acá vía
 * {@link StudyRepository#existsProfessionalFeedbackCandidate} y se vuelve a reforzar en la capa de base de datos
 * con un constraint {@code UNIQUE(source, correctionId)}). Cada fila capturada empieza en
 * {@code status="captured"} — este servicio nunca eleva el estado de un candidato; eso es una
 * acción explícita, separada y emitida por una persona (ver {@code ProfessionalFeedbackController}).
 */
@Service
public class ProfessionalFeedbackCaptureService {
    private final StudyRepository repository;
    private final EffectiveAnnotationResolver annotationResolver;
    private final Clock clock;

    @Autowired
    public ProfessionalFeedbackCaptureService(StudyRepository repository, EffectiveAnnotationResolver annotationResolver) {
        this(repository, annotationResolver, Clock.systemUTC());
    }

    ProfessionalFeedbackCaptureService(StudyRepository repository, EffectiveAnnotationResolver annotationResolver, Clock clock) {
        this.repository = repository;
        this.annotationResolver = annotationResolver;
        this.clock = clock;
    }

    public List<ProfessionalFeedbackCandidate> captureForStudy(String caseId) {
        Study study = repository.findStudyByCaseId(caseId).orElseThrow(() -> new StudyNotFoundException(caseId));
        List<ProfessionalFeedbackCandidate> captured = new ArrayList<>();
        for (AiProductAnalysisRun run : repository.findAiProductAnalysisRunsByStudyId(study.id())) {
            captured.addAll(captureMeasurementCorrections(study, run));
            captured.addAll(captureEvidenceRegionCorrectionsAndNewFindings(study, run));
        }
        return captured;
    }

    private List<ProfessionalFeedbackCandidate> captureMeasurementCorrections(Study study, AiProductAnalysisRun run) {
        Map<String, AiMeasurementResult> measurementsById = repository.findAiMeasurementResultsByAnalysisRunId(run.id()).stream()
            .collect(java.util.stream.Collectors.toMap(AiMeasurementResult::id, measurement -> measurement, (a, b) -> a));
        List<ProfessionalFeedbackCandidate> captured = new ArrayList<>();
        for (AiMeasurementCorrection correction : repository.findAiMeasurementCorrectionsByAnalysisRunId(run.id())) {
            if (repository.existsProfessionalFeedbackCandidate("measurement", correction.id())) continue;
            AiMeasurementResult measurement = measurementsById.get(correction.measurementId());
            ProfessionalFeedbackCandidate candidate = new ProfessionalFeedbackCandidate(
                UUID.randomUUID().toString(),
                study.id(),
                study.caseId(),
                run.id(),
                "measurement",
                correction.id(),
                measurement == null ? null : measurement.level(),
                measurement == null ? null : measurement.plane(),
                measurement == null ? null : measurement.structure(),
                correction.field(),
                null,
                correction.originalValue(),
                null,
                correction.correctedValue(),
                null,
                null,
                null,
                null,
                correction.reviewedBy(),
                correction.comment(),
                "captured",
                correction.createdAt(),
                Instant.now(clock),
                null, null, null
            );
            captured.add(repository.saveProfessionalFeedbackCandidate(candidate));
        }
        return captured;
    }

    private List<ProfessionalFeedbackCandidate> captureEvidenceRegionCorrectionsAndNewFindings(Study study, AiProductAnalysisRun run) {
        Map<String, AiEvidenceRegion> regionsById = repository.findAiEvidenceRegionsByAnalysisRunId(run.id()).stream()
            .collect(java.util.stream.Collectors.toMap(AiEvidenceRegion::id, region -> region, (a, b) -> a));
        List<ProfessionalFeedbackCandidate> captured = new ArrayList<>();

        for (AiEvidenceRegionCorrection correction : repository.findAiEvidenceRegionCorrectionsByAnalysisRunId(run.id())) {
            if (repository.existsProfessionalFeedbackCandidate("evidence_region", correction.id())) continue;
            AiEvidenceRegion region = regionsById.get(correction.evidenceRegionId());
            ProfessionalFeedbackCandidate candidate = new ProfessionalFeedbackCandidate(
                UUID.randomUUID().toString(),
                study.id(),
                study.caseId(),
                run.id(),
                "evidence_region",
                correction.id(),
                region == null ? null : region.level(),
                region == null ? null : region.plane(),
                region == null ? null : region.structure(),
                null,
                correction.correctedGeometryType(),
                null,
                correction.originalGeometry(),
                null,
                correction.correctedGeometry(),
                region == null ? null : region.modelId(),
                region == null ? null : region.modelSha256(),
                region == null ? null : region.pipelineVersion(),
                correction.reviewedBy(),
                correction.comment(),
                "captured",
                correction.createdAt(),
                Instant.now(clock),
                null, null, null
            );
            captured.add(repository.saveProfessionalFeedbackCandidate(candidate));
        }

        // Una región de autoría profesional que la IA nunca sugirió es feedback por derecho propio —
        // "distinguirse de AI suggested/validated/corrected" (spec EXT-07) — capturada directamente
        // de la propia fila de la región (no hay fila de corrección para ella), nunca forzada hacia el
        // modelo automáticamente.
        for (AiEvidenceRegion region : regionsById.values()) {
            if (!"professional".equals(region.origin())) continue;
            if (repository.existsProfessionalFeedbackCandidate("professional_new_finding", region.id())) continue;
            ProfessionalFeedbackCandidate candidate = new ProfessionalFeedbackCandidate(
                UUID.randomUUID().toString(),
                study.id(),
                study.caseId(),
                run.id(),
                "professional_new_finding",
                region.id(),
                region.level(),
                region.plane(),
                region.structure(),
                null,
                region.geometryType(),
                null,
                null,
                null,
                region.geometry(),
                null,
                null,
                null,
                region.authoredBy(),
                region.comment(),
                "captured",
                region.createdAt(),
                Instant.now(clock),
                null, null, null
            );
            captured.add(repository.saveProfessionalFeedbackCandidate(candidate));
        }

        // EXT-04B5: un profesional que RECHAZA una sugerencia de la IA (sin corrección — una corrección
        // ya toma la rama "evidence_region" de arriba sin importar el estado de revisión) también es
        // feedback por derecho propio: una señal negativa, distinta de "nunca revisada en absoluto"
        // (AI_SUGGESTED, no efectiva, pero todavía `pending` — nunca capturada). Usa la misma
        // resolución de estado de revisión que EffectiveAnnotationResolver ya aplica (revisión
        // de nivel de disco cuando la región nombra uno, revisión a nivel de corrida en caso contrario) en lugar de
        // volver a derivarla acá.
        Map<String, String> effectiveReviewStatusByRegionId = annotationResolver.effectiveAnnotations(run, true).stream()
            .collect(java.util.stream.Collectors.toMap(a -> a.region().id(), EffectiveAnnotationResolver.EffectiveAnnotation::reviewStatus, (a, b) -> a));
        for (AiEvidenceRegion region : regionsById.values()) {
            if (!"ai".equals(region.origin())) continue;
            if (!"rejected".equals(effectiveReviewStatusByRegionId.get(region.id()))) continue;
            if (repository.existsProfessionalFeedbackCandidate("rejected_ai_suggestion", region.id())) continue;
            ProfessionalFeedbackCandidate candidate = new ProfessionalFeedbackCandidate(
                UUID.randomUUID().toString(),
                study.id(),
                study.caseId(),
                run.id(),
                "rejected_ai_suggestion",
                region.id(),
                region.level(),
                region.plane(),
                region.structure(),
                null,
                region.geometryType(),
                null,
                region.geometry(),
                null,
                null,
                region.modelId(),
                region.modelSha256(),
                region.pipelineVersion(),
                rejectionReviewer(run, region),
                region.comment(),
                "captured",
                region.createdAt(),
                Instant.now(clock),
                null, null, null
            );
            captured.add(repository.saveProfessionalFeedbackCandidate(candidate));
        }
        return captured;
    }

    /** El mismo orden de fallback que ya usan StudyReportService/EffectiveAnnotationResolver: revisión de nivel de disco cuando la región nombra uno, revisión a nivel de corrida en caso contrario. */
    private String rejectionReviewer(AiProductAnalysisRun run, AiEvidenceRegion region) {
        if (region.discLevelResultId() != null) {
            for (AiDiscLevelReview review : repository.findAiDiscLevelReviewsByAnalysisRunId(run.id())) {
                if (review.discLevelResultId().equals(region.discLevelResultId())) return review.reviewedBy();
            }
        }
        return repository.findAiProductAnalysisRunReview(run.id()).map(r -> r.reviewedBy()).orElse(null);
    }

    public List<ProfessionalFeedbackCandidate> findByCase(String caseId) {
        return repository.findProfessionalFeedbackCandidatesByCaseId(caseId);
    }

    /**
     * EXT-05: la misma lista que {@link #findByCase}, acotada por los filtros que no sean null.
     * El filtrado ocurre en memoria sobre el conjunto de candidatos del propio caso (chico, a escala sintética) —
     * no hizo falta ninguna nueva cláusula SQL WHERE para los volúmenes de datos de esta etapa.
     */
    public List<ProfessionalFeedbackCandidate> findByCase(String caseId, FeedbackCandidateFilter filter) {
        return findByCase(caseId).stream().filter(filter::matches).toList();
    }

    public record FeedbackCandidateFilter(String status, String source, String level, String plane, String modelId, Instant from, Instant to) {
        boolean matches(ProfessionalFeedbackCandidate candidate) {
            if (status != null && !status.equals(candidate.status())) return false;
            if (source != null && !source.equals(candidate.source())) return false;
            if (level != null && !level.equals(candidate.level())) return false;
            if (plane != null && !plane.equals(candidate.plane())) return false;
            if (modelId != null && !modelId.equals(candidate.modelId())) return false;
            if (from != null && candidate.correctedAt().isBefore(from)) return false;
            if (to != null && candidate.correctedAt().isAfter(to)) return false;
            return true;
        }
    }

    public Optional<ProfessionalFeedbackCandidate> findById(String candidateId) {
        return repository.findProfessionalFeedbackCandidateById(candidateId);
    }

    public List<ProfessionalFeedbackStatusChange> findHistory(String candidateId) {
        return repository.findFeedbackCandidateStatusChanges(candidateId);
    }

    /**
     * La única forma en que cambia el estado de un candidato — siempre una llamada explícita emitida por una persona.
     * {@code "ready_for_training"} no es un valor que este producto modele en absoluto (ver
     * {@link ProfessionalFeedbackCandidate#ALLOWED_STATUSES}), así que se rechaza igual que cualquier
     * otro estado no reconocido, nunca se acepta en silencio. Cada llamada agrega una fila a la
     * tabla separada de historial de cambios de estado — nunca simplemente sobrescribe el estado del candidato.
     */
    public ProfessionalFeedbackCandidate curate(String candidateId, String status, String curatorId, String reason, String note) {
        if (!ProfessionalFeedbackCandidate.ALLOWED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Estado de feedback inválido: " + status);
        }
        repository.findProfessionalFeedbackCandidateById(candidateId)
            .orElseThrow(() -> new IllegalArgumentException("Candidato de feedback no encontrado: " + candidateId));
        return repository.recordFeedbackCandidateStatusChange(candidateId, status, curatorId, reason, note, Instant.now(clock));
    }

    /**
     * EXT-05: un manifiesto de exportación interno y curado — nunca un dataset de entrenamiento producido automáticamente
     * (ver el javadoc de la clase: todo este archivo solo captura/cura, nunca entrena).
     * {@code sourceModelVersions} es el conjunto distinto y ordenado de valores {@code modelId} no nulos
     * entre los candidatos exportados; {@code manifestChecksum} es un SHA-256 sobre el
     * JSON propio de los registros exportados para que un consumidor posterior pueda detectar si el archivo de exportación fue alterado.
     */
    public FeedbackDatasetExport exportForStudy(String caseId, FeedbackCandidateFilter filter, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        List<ProfessionalFeedbackCandidate> candidates = filter == null ? findByCase(caseId) : findByCase(caseId, filter);
        // Exportado a través del mismo DTO público que usa cualquier otro endpoint — nunca el
        // record de dominio directamente, que filtraría reviewedBy/curatorId al archivo de exportación.
        List<ar.edu.uade.pfi.backend.dto.ProfessionalFeedbackCandidateDto> records = candidates.stream()
            .map(ar.edu.uade.pfi.backend.dto.ProfessionalFeedbackCandidateDto::from)
            .toList();
        List<String> sourceModelVersions = candidates.stream()
            .map(ProfessionalFeedbackCandidate::modelId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
        String checksum;
        try {
            byte[] recordsJson = objectMapper.writeValueAsBytes(records);
            checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(recordsJson));
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo generar el checksum del export", ex);
        }
        return new FeedbackDatasetExport("pfi.feedback-dataset-export.v1", Instant.now(clock), records.size(), sourceModelVersions, records, checksum);
    }

    public record FeedbackDatasetExport(
        String schemaVersion, Instant exportedAt, int candidateCount,
        List<String> sourceModelVersions, List<ar.edu.uade.pfi.backend.dto.ProfessionalFeedbackCandidateDto> records, String manifestChecksum
    ) {
    }
}
