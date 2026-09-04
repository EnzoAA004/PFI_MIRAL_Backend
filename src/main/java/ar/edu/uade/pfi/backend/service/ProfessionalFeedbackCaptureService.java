package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelReview;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegionCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.ProfessionalFeedbackCandidate;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * EXT-07D: CAPTURE only — never training, never online/active learning, never a runtime
 * checkpoint change. This service reads professional corrections and professional-authored
 * findings that already exist (append-only, produced by review/correction flows this product
 * has had since Stage 78/EXT-04B2) and captures a structured, traceable
 * {@link ProfessionalFeedbackCandidate} row for each one it has not already captured.
 *
 * <p>Idempotent: capturing the same correction/new-finding twice is a no-op (checked here via
 * {@link StudyRepository#existsProfessionalFeedbackCandidate} and enforced again at the database
 * layer by a {@code UNIQUE(source, correctionId)} constraint). Every captured row starts at
 * {@code status="captured"} — this service never elevates a candidate's status; that is an
 * explicit, separate, human-issued action (see {@code ProfessionalFeedbackController}).
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
                Instant.now(clock)
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
                Instant.now(clock)
            );
            captured.add(repository.saveProfessionalFeedbackCandidate(candidate));
        }

        // A professional-authored region the AI never suggested is feedback in its own right —
        // "distinguirse de AI suggested/validated/corrected" (EXT-07 spec) — captured directly
        // from the region row itself (there is no correction row for it), never forced into the
        // model automatically.
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
                Instant.now(clock)
            );
            captured.add(repository.saveProfessionalFeedbackCandidate(candidate));
        }

        // EXT-04B5: a professional REJECTING an AI suggestion (with no correction — a correction
        // already takes the "evidence_region" branch above regardless of review status) is also
        // feedback in its own right: a negative signal, distinct from "never reviewed at all"
        // (AI_SUGGESTED, not effective, but still `pending` — never captured). Uses the same
        // review-status resolution EffectiveAnnotationResolver already applies (disc-level
        // review when the region names one, run-level review otherwise) rather than
        // re-deriving it here.
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
                Instant.now(clock)
            );
            captured.add(repository.saveProfessionalFeedbackCandidate(candidate));
        }
        return captured;
    }

    /** Same fallback order StudyReportService/EffectiveAnnotationResolver already use: disc-level review when the region names one, run-level review otherwise. */
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
     * The only way a candidate's status ever changes — always an explicit, human-issued call.
     * {@code "ready_for_training"} is not a value this product models at all (see
     * {@link ProfessionalFeedbackCandidate#ALLOWED_STATUSES}), so it is rejected the same as any
     * other unrecognised status, never silently accepted.
     */
    public ProfessionalFeedbackCandidate updateStatus(String candidateId, String status) {
        if (!ProfessionalFeedbackCandidate.ALLOWED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Estado de feedback inválido: " + status);
        }
        return repository.updateProfessionalFeedbackCandidateStatus(candidateId, status);
    }
}
