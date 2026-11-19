package ar.edu.uade.pfi.backend.repository;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiDiscLevelReview;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegionCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.ProfessionalFeedbackCandidate;
import ar.edu.uade.pfi.backend.domain.ProfessionalFeedbackStatusChange;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRunReview;
import ar.edu.uade.pfi.backend.domain.ChatConversation;
import ar.edu.uade.pfi.backend.domain.ChatMessage;
import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.ReviewerAnnotation;
import ar.edu.uade.pfi.backend.domain.RunReview;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "pfi.persistence.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryStudyRepository implements StudyRepository {
    private final ConcurrentMap<String, Study> studiesById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> studyIdsByCaseId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> studyOwnersByCaseId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> studyGrants = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, InputResource> inputsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, StudyRun> runsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> runIdsByMultiplanarRunId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> runIdsByTraceId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<MeasurementCorrection>> correctionsByRunId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<ReviewerAnnotation>> annotationsByRunId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DomainAuditEvent> auditEventsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AiProductAnalysisRun> aiAnalysisRunsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<AiDiscLevelResult>> aiDiscLevelResultsByRunId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<AiMeasurementResult>> aiMeasurementResultsByRunId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AiProductAnalysisRunReview> aiRunReviewsByRunId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AiDiscLevelReview> aiDiscLevelReviewsByDiscLevelId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<AiMeasurementCorrection>> aiMeasurementCorrectionsByRunId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<AiEvidenceRegion>> aiEvidenceRegionsByRunId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<AiEvidenceRegionCorrection>> aiEvidenceRegionCorrectionsByRunId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ProfessionalFeedbackCandidate> professionalFeedbackCandidatesById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<ProfessionalFeedbackStatusChange>> feedbackStatusChangesByCandidateId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ChatConversation> chatConversationsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<ChatMessage>> chatMessagesByConversationId = new ConcurrentHashMap<>();

    @Override
    public Study saveStudy(Study study) {
        studiesById.put(study.id(), study);
        studyIdsByCaseId.put(study.caseId(), study.id());
        return study;
    }

    @Override
    public InputResource saveInput(InputResource input) {
        inputsById.put(input.id(), input);
        return input;
    }

    @Override
    public StudyRun saveRun(StudyRun run) {
        runsById.put(run.id(), run);
        runIdsByMultiplanarRunId.put(run.multiplanarRunId(), run.id());
        runIdsByTraceId.put(run.traceId(), run.id());
        return run;
    }

    @Override
    public List<Study> findAllStudies() {
        return studiesById.values().stream()
            .sorted(Comparator.comparing(Study::updatedAt).reversed())
            .toList();
    }

    @Override
    public Optional<Study> findStudyByCaseId(String caseId) {
        return Optional.ofNullable(studyIdsByCaseId.get(caseId)).map(studiesById::get);
    }

    @Override
    public boolean claimStudyOwner(String caseId, String principalId) {
        if (caseId == null || principalId == null || principalId.isBlank()) return false;
        String owner = studyOwnersByCaseId.putIfAbsent(caseId, principalId);
        return owner == null || owner.equals(principalId);
    }

    @Override
    public boolean hasStudyAccess(String caseId, String principalId, boolean write) {
        if (caseId == null || principalId == null) return false;
        String owner = studyOwnersByCaseId.get(caseId);
        if (principalId.equals(owner)) return true;
        String permission = studyGrants.get(caseId + "\u0000" + principalId);
        return "WRITE".equals(permission) || (!write && "READ".equals(permission));
    }

    @Override
    public boolean isStudyOwner(String caseId, String principalId) {
        return caseId != null && principalId != null && principalId.equals(studyOwnersByCaseId.get(caseId));
    }

    @Override
    public void grantStudyAccess(String caseId, String principalId, String permission, String grantedBy) {
        if (!grantedBy.equals(studyOwnersByCaseId.get(caseId))) throw new IllegalStateException("Only the study owner can grant access");
        if ("OWNER".equals(permission) || !List.of("READ", "WRITE").contains(permission)) throw new IllegalArgumentException("Invalid study permission");
        studyGrants.put(caseId + "\u0000" + principalId, permission);
    }

    @Override
    public List<String> findAccessibleCaseIds(String principalId) {
        List<String> caseIds = new ArrayList<>();
        studyOwnersByCaseId.forEach((caseId, owner) -> {
            if (principalId.equals(owner) || studyGrants.containsKey(caseId + "\u0000" + principalId)) caseIds.add(caseId);
        });
        return List.copyOf(caseIds);
    }

    @Override
    public Optional<String> resolveStudyCaseId(String resourceId) {
        if (resourceId == null) return Optional.empty();
        AiProductAnalysisRun analysis = aiAnalysisRunsById.get(resourceId);
        if (analysis != null) {
            Study study = studiesById.get(analysis.studyId());
            if (study != null) return Optional.of(study.caseId());
        }
        ProfessionalFeedbackCandidate candidate = professionalFeedbackCandidatesById.get(resourceId);
        if (candidate != null) return Optional.of(candidate.caseId());
        for (InputResource input : inputsById.values()) {
            if (resourceId.equals(input.inputId())) {
                Study study = studiesById.get(input.studyId());
                if (study != null) return Optional.of(study.caseId());
            }
        }
        for (StudyRun run : runsById.values()) {
            if (resourceId.equals(run.multiplanarRunId()) || resourceId.equals(run.sagittalRunId()) || resourceId.equals(run.axialRunId())) {
                Study study = studiesById.get(run.studyId());
                if (study != null) return Optional.of(study.caseId());
            }
            if (run.artifacts().stream().anyMatch(artifact -> resourceId.equals(artifact.runId()))) {
                Study study = studiesById.get(run.studyId());
                if (study != null) return Optional.of(study.caseId());
            }
        }
        return Optional.empty();
    }

    @Override
    public List<Study> findStudiesBySubjectRef(String subjectRef) {
        String normalized = subjectRef == null ? "" : subjectRef.trim();
        return studiesById.values().stream()
            .filter(study -> study.subjectRef() != null && study.subjectRef().equalsIgnoreCase(normalized))
            .sorted(Comparator
                .comparing((Study study) -> study.studyDate() == null)
                .thenComparing(Study::studyDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Study::createdAt, Comparator.reverseOrder()))
            .toList();
    }

    @Override
    public List<Study> findStudiesByPatientId(String patientId) {
        return studiesById.values().stream()
            .filter(study -> patientId.equals(study.patientId()))
            .sorted(Comparator
                .comparing((Study study) -> study.studyDate() == null)
                .thenComparing(Study::studyDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Study::createdAt, Comparator.reverseOrder())
                .thenComparing(Study::id))
            .toList();
    }

    @Override
    public synchronized Optional<Study> updatePatientIfExpected(String caseId, String targetPatientId, String expectedPatientId) {
        Study current = findStudyByCaseId(caseId).orElse(null);
        if (current == null || !java.util.Objects.equals(current.patientId(), expectedPatientId)) {
            return Optional.empty();
        }
        Study updated = new Study(
            current.id(),
            current.caseId(),
            current.status(),
            targetPatientId,
            current.subjectRef(),
            current.studyDate(),
            current.modality(),
            current.description(),
            current.reviewPriority(),
            current.createdAt(),
            current.updatedAt()
        );
        studiesById.put(updated.id(), updated);
        return Optional.of(updated);
    }

    @Override
    public List<InputResource> findInputsByStudyId(String studyId) {
        List<InputResource> inputs = new ArrayList<>();
        for (InputResource input : inputsById.values()) {
            if (input.studyId().equals(studyId)) inputs.add(input);
        }
        return inputs;
    }

    @Override
    public List<StudyRun> findRunsByStudyId(String studyId) {
        return runsById.values().stream()
            .filter(run -> run.studyId().equals(studyId))
            .sorted(Comparator.comparing(StudyRun::createdAt).reversed())
            .toList();
    }

    @Override
    public Optional<StudyRun> findLatestRunByStudyId(String studyId) {
        return findRunsByStudyId(studyId).stream().findFirst();
    }

    @Override
    public Optional<StudyRun> findRunByMultiplanarRunId(String multiplanarRunId) {
        return Optional.ofNullable(runIdsByMultiplanarRunId.get(multiplanarRunId)).map(runsById::get);
    }

    @Override
    public Optional<StudyRun> findRunByTraceId(String traceId) {
        return Optional.ofNullable(runIdsByTraceId.get(traceId)).map(runsById::get);
    }

    @Override
    public List<RunArtifact> findArtifactsByRunId(String studyRunId) {
        return runsById.values().stream()
            .filter(run -> run.id().equals(studyRunId))
            .findFirst()
            .map(StudyRun::artifacts)
            .orElse(List.of());
    }

    @Override
    public Optional<RunArtifact> findArtifactByRunPlaneAndName(String runId, String plane, String assetName) {
        return runsById.values().stream()
            .flatMap(run -> run.artifacts().stream())
            .filter(artifact -> artifact.runId().equals(runId) && artifact.plane().equals(plane) && artifact.assetName().equals(assetName))
            .findFirst();
    }

    @Override
    public RunArtifact updateArtifactStorage(String artifactId, String storageStatus, String storageKind, Long sizeBytes, String sha256) {
        for (StudyRun run : runsById.values()) {
            List<RunArtifact> updatedArtifacts = new ArrayList<>();
            RunArtifact updated = null;
            for (RunArtifact artifact : run.artifacts()) {
                if (artifact.id().equals(artifactId)) {
                    updated = new RunArtifact(
                        artifact.id(),
                        artifact.studyRunId(),
                        artifact.runId(),
                        artifact.plane(),
                        artifact.assetName(),
                        artifact.contentType(),
                        artifact.artifactRef(),
                        artifact.createdAt(),
                        storageStatus,
                        storageKind,
                        sizeBytes,
                        sha256
                    );
                    updatedArtifacts.add(updated);
                } else {
                    updatedArtifacts.add(artifact);
                }
            }
            if (updated != null) {
                runsById.put(run.id(), new StudyRun(
                    run.id(),
                    run.studyId(),
                    run.multiplanarRunId(),
                    run.traceId(),
                    run.requestedInferenceMode(),
                    run.effectiveInferenceMode(),
                    run.sagittalModelKey(),
                    run.axialModelKey(),
                    run.sagittalArtifactHash(),
                    run.axialArtifactHash(),
                    run.sagittalRunId(),
                    run.axialRunId(),
                    run.assets(),
                    run.metricsSnapshot(),
                    updatedArtifacts,
                    run.status(),
                    run.reviewStatus(),
                    run.reviewer(),
                    run.reviewedAt(),
                    run.comments(),
                    run.createdAt(),
                    run.updatedAt()
                ));
                return updated;
            }
        }
        throw new IllegalArgumentException("Artifact not found");
    }

    @Override
    public StudyRun updateRunMetricsSnapshot(String multiplanarRunId, Map<String, Object> metricsSnapshot) {
        for (StudyRun run : runsById.values()) {
            if (!run.multiplanarRunId().equals(multiplanarRunId)) continue;
            StudyRun updated = new StudyRun(
                run.id(),
                run.studyId(),
                run.multiplanarRunId(),
                run.traceId(),
                run.requestedInferenceMode(),
                run.effectiveInferenceMode(),
                run.sagittalModelKey(),
                run.axialModelKey(),
                run.sagittalArtifactHash(),
                run.axialArtifactHash(),
                run.sagittalRunId(),
                run.axialRunId(),
                run.assets(),
                metricsSnapshot,
                run.artifacts(),
                run.status(),
                run.reviewStatus(),
                run.reviewer(),
                run.reviewedAt(),
                run.comments(),
                run.createdAt(),
                run.updatedAt()
            );
            runsById.put(run.id(), updated);
            return updated;
        }
        throw new IllegalArgumentException("Run not found");
    }

    @Override
    public RunReview saveReview(String multiplanarRunId, String reviewStatus, String reviewer, Instant reviewedAt, String comments, List<MeasurementCorrection> corrections) {
        return saveReview(multiplanarRunId, reviewStatus, reviewer, reviewedAt, comments, corrections, null);
    }

    @Override
    public RunReview saveReview(String multiplanarRunId, String reviewStatus, String reviewer, Instant reviewedAt, String comments, List<MeasurementCorrection> corrections, DomainAuditEvent auditEvent) {
        StudyRun existing = findRunByMultiplanarRunId(multiplanarRunId).orElseThrow();
        Instant updatedAt = reviewedAt == null ? Instant.now() : reviewedAt;
        StudyRun updated = new StudyRun(
            existing.id(),
            existing.studyId(),
            existing.multiplanarRunId(),
            existing.traceId(),
            existing.requestedInferenceMode(),
            existing.effectiveInferenceMode(),
            existing.sagittalModelKey(),
            existing.axialModelKey(),
            existing.sagittalArtifactHash(),
            existing.axialArtifactHash(),
            existing.sagittalRunId(),
            existing.axialRunId(),
            existing.assets(),
            existing.metricsSnapshot(),
            existing.artifacts(),
            existing.status(),
            reviewStatus,
            reviewer,
            reviewedAt,
            comments,
            existing.createdAt(),
            updatedAt
        );
        saveRun(updated);
        correctionsByRunId.put(existing.id(), List.copyOf(corrections));
        if (auditEvent != null) saveAuditEvent(auditEvent);
        return new RunReview(multiplanarRunId, existing.traceId(), reviewStatus, reviewer, reviewedAt, comments, corrections);
    }

    @Override
    public Optional<RunReview> findReviewByMultiplanarRunId(String multiplanarRunId) {
        return findRunByMultiplanarRunId(multiplanarRunId)
            .map(run -> new RunReview(
                run.multiplanarRunId(),
                run.traceId(),
                run.reviewStatus(),
                run.reviewer(),
                run.reviewedAt(),
                run.comments(),
                correctionsByRunId.getOrDefault(run.id(), List.of())
            ));
    }

    @Override
    public List<MeasurementCorrection> findCorrectionsByStudyRunId(String studyRunId) {
        return correctionsByRunId.getOrDefault(studyRunId, List.of());
    }

    @Override
    public List<ReviewerAnnotation> replaceAnnotations(String multiplanarRunId, List<ReviewerAnnotation> annotations) {
        StudyRun run = findRunByMultiplanarRunId(multiplanarRunId).orElseThrow();
        List<ReviewerAnnotation> stored = List.copyOf(annotations);
        annotationsByRunId.put(run.id(), stored);
        return stored;
    }

    @Override
    public List<ReviewerAnnotation> findAnnotationsByRunId(String multiplanarRunId) {
        return findRunByMultiplanarRunId(multiplanarRunId)
            .map(run -> annotationsByRunId.getOrDefault(run.id(), List.<ReviewerAnnotation>of()))
            .orElse(List.of());
    }

    @Override
    public DomainAuditEvent saveAuditEvent(DomainAuditEvent event) {
        auditEventsById.put(event.id(), event);
        return event;
    }

    @Override
    public List<DomainAuditEvent> findAuditEventsByTraceId(String traceId) {
        return auditEventsById.values().stream()
            .filter(event -> event.traceId().equals(traceId))
            .toList();
    }

    @Override
    public List<DomainAuditEvent> findAuditEventsByEntityId(String entityId) {
        return auditEventsById.values().stream()
            .filter(event -> event.entityId().equals(entityId))
            .toList();
    }

    @Override
    public List<DomainAuditEvent> findAuditEventsByStudyId(String studyId) {
        List<String> runIds = findRunsByStudyId(studyId).stream().map(StudyRun::multiplanarRunId).toList();
        return auditEventsById.values().stream()
            .filter(event -> runIds.contains(event.entityId()) || studyId.equals(event.entityId()))
            .toList();
    }

    // ---- Stage 76: pfi.ai-product-result.v1 structured persistence ----

    @Override
    public AiProductAnalysisRun saveAiProductAnalysisRun(AiProductAnalysisRun run, List<AiDiscLevelResult> discLevels, List<AiMeasurementResult> measurements) {
        aiAnalysisRunsById.put(run.id(), run);
        aiDiscLevelResultsByRunId.put(run.id(), discLevels == null ? List.of() : List.copyOf(discLevels));
        aiMeasurementResultsByRunId.put(run.id(), measurements == null ? List.of() : List.copyOf(measurements));
        return run;
    }

    @Override
    public List<AiProductAnalysisRun> findAiProductAnalysisRunsByStudyId(String studyId) {
        return aiAnalysisRunsById.values().stream()
            .filter(run -> run.studyId().equals(studyId))
            .sorted(Comparator.comparing(AiProductAnalysisRun::createdAt).reversed())
            .toList();
    }

    @Override
    public Optional<AiProductAnalysisRun> findAiProductAnalysisRunById(String id) {
        return Optional.ofNullable(aiAnalysisRunsById.get(id));
    }

    @Override
    public List<AiDiscLevelResult> findAiDiscLevelResultsByAnalysisRunId(String analysisRunId) {
        return aiDiscLevelResultsByRunId.getOrDefault(analysisRunId, List.of()).stream()
            .sorted(Comparator.comparingInt(AiDiscLevelResult::rankIndex))
            .toList();
    }

    @Override
    public List<AiMeasurementResult> findAiMeasurementResultsByAnalysisRunId(String analysisRunId) {
        return aiMeasurementResultsByRunId.getOrDefault(analysisRunId, List.of());
    }

    // ---- Stage 78: professional review / correction workflow ----

    @Override
    public AiProductAnalysisRunReview upsertAiProductAnalysisRunReview(String analysisRunId, String reviewStatus, String reviewComment, String reviewedBy, Instant reviewedAt) {
        AiProductAnalysisRunReview review = new AiProductAnalysisRunReview(
            UUID.randomUUID().toString(), analysisRunId, reviewStatus, reviewComment, reviewedBy, reviewedAt, Instant.now()
        );
        aiRunReviewsByRunId.put(analysisRunId, review);
        return review;
    }

    @Override
    public Optional<AiProductAnalysisRunReview> findAiProductAnalysisRunReview(String analysisRunId) {
        return Optional.ofNullable(aiRunReviewsByRunId.get(analysisRunId));
    }

    @Override
    public AiDiscLevelReview upsertAiDiscLevelReview(String discLevelResultId, String reviewStatus, String reviewComment, String reviewedBy, Instant reviewedAt) {
        AiDiscLevelReview review = new AiDiscLevelReview(
            UUID.randomUUID().toString(), discLevelResultId, reviewStatus, reviewComment, reviewedBy, reviewedAt, Instant.now()
        );
        aiDiscLevelReviewsByDiscLevelId.put(discLevelResultId, review);
        return review;
    }

    @Override
    public Optional<AiDiscLevelReview> findAiDiscLevelReview(String discLevelResultId) {
        return Optional.ofNullable(aiDiscLevelReviewsByDiscLevelId.get(discLevelResultId));
    }

    @Override
    public List<AiDiscLevelReview> findAiDiscLevelReviewsByAnalysisRunId(String analysisRunId) {
        List<String> discLevelIds = aiDiscLevelResultsByRunId.getOrDefault(analysisRunId, List.of()).stream()
            .map(AiDiscLevelResult::id)
            .toList();
        return aiDiscLevelReviewsByDiscLevelId.values().stream()
            .filter(review -> discLevelIds.contains(review.discLevelResultId()))
            .toList();
    }

    @Override
    public AiMeasurementCorrection saveAiMeasurementCorrection(AiMeasurementCorrection correction) {
        aiMeasurementCorrectionsByRunId.compute(correction.analysisRunId(), (key, existing) -> {
            List<AiMeasurementCorrection> updated = new ArrayList<>(existing == null ? List.of() : existing);
            updated.add(correction);
            return updated;
        });
        return correction;
    }

    @Override
    public List<AiMeasurementCorrection> findAiMeasurementCorrectionsByAnalysisRunId(String analysisRunId) {
        return aiMeasurementCorrectionsByRunId.getOrDefault(analysisRunId, List.of());
    }

    // ---- EXT-04B2: visual evidence regions ----

    @Override
    public AiEvidenceRegion saveAiEvidenceRegion(AiEvidenceRegion region) {
        aiEvidenceRegionsByRunId.compute(region.analysisRunId(), (key, existing) -> {
            List<AiEvidenceRegion> updated = new ArrayList<>(existing == null ? List.of() : existing);
            updated.add(region);
            return updated;
        });
        return region;
    }

    @Override
    public List<AiEvidenceRegion> findAiEvidenceRegionsByAnalysisRunId(String analysisRunId) {
        return aiEvidenceRegionsByRunId.getOrDefault(analysisRunId, List.of());
    }

    @Override
    public AiEvidenceRegionCorrection saveAiEvidenceRegionCorrection(AiEvidenceRegionCorrection correction) {
        aiEvidenceRegionCorrectionsByRunId.compute(correction.analysisRunId(), (key, existing) -> {
            List<AiEvidenceRegionCorrection> updated = new ArrayList<>(existing == null ? List.of() : existing);
            updated.add(correction);
            return updated;
        });
        return correction;
    }

    @Override
    public List<AiEvidenceRegionCorrection> findAiEvidenceRegionCorrectionsByAnalysisRunId(String analysisRunId) {
        return aiEvidenceRegionCorrectionsByRunId.getOrDefault(analysisRunId, List.of());
    }

    // ---- EXT-07D: professional feedback capture (never training) ----

    @Override
    public ProfessionalFeedbackCandidate saveProfessionalFeedbackCandidate(ProfessionalFeedbackCandidate candidate) {
        professionalFeedbackCandidatesById.put(candidate.id(), candidate);
        return candidate;
    }

    @Override
    public boolean existsProfessionalFeedbackCandidate(String source, String correctionId) {
        return professionalFeedbackCandidatesById.values().stream()
            .anyMatch(candidate -> candidate.source().equals(source) && candidate.correctionId().equals(correctionId));
    }

    @Override
    public List<ProfessionalFeedbackCandidate> findProfessionalFeedbackCandidatesByCaseId(String caseId) {
        return professionalFeedbackCandidatesById.values().stream()
            .filter(candidate -> candidate.caseId().equals(caseId))
            .sorted(java.util.Comparator.comparing(ProfessionalFeedbackCandidate::correctedAt))
            .toList();
    }

    @Override
    public Optional<ProfessionalFeedbackCandidate> findProfessionalFeedbackCandidateById(String id) {
        return Optional.ofNullable(professionalFeedbackCandidatesById.get(id));
    }

    @Override
    public ProfessionalFeedbackCandidate recordFeedbackCandidateStatusChange(String id, String toStatus, String curatorId, String reason, String note, java.time.Instant changedAt) {
        ProfessionalFeedbackCandidate existing = professionalFeedbackCandidatesById.get(id);
        if (existing == null) throw new IllegalStateException("Professional feedback candidate not found: " + id);
        ProfessionalFeedbackCandidate updated = new ProfessionalFeedbackCandidate(
            existing.id(), existing.studyId(), existing.caseId(), existing.analysisRunId(), existing.source(),
            existing.correctionId(), existing.level(), existing.plane(), existing.structure(), existing.metric(),
            existing.geometryType(), existing.aiOriginalValue(), existing.aiOriginalGeometry(), existing.professionalValue(),
            existing.professionalGeometry(), existing.modelId(), existing.modelSha256(), existing.pipelineVersion(),
            existing.reviewedBy(), existing.note(), toStatus, existing.correctedAt(), existing.capturedAt(),
            curatorId, changedAt, "excluded".equals(toStatus) ? reason : existing.exclusionReason()
        );
        professionalFeedbackCandidatesById.put(id, updated);
        feedbackStatusChangesByCandidateId.computeIfAbsent(id, key -> new java.util.concurrent.CopyOnWriteArrayList<>())
            .add(new ProfessionalFeedbackStatusChange(java.util.UUID.randomUUID().toString(), id, existing.status(), toStatus, curatorId, reason, note, changedAt));
        return updated;
    }

    @Override
    public List<ProfessionalFeedbackStatusChange> findFeedbackCandidateStatusChanges(String candidateId) {
        return feedbackStatusChangesByCandidateId.getOrDefault(candidateId, List.of());
    }

    // ---- Stage 80: contextual chat ----

    @Override
    public ChatConversation saveChatConversation(ChatConversation conversation) {
        chatConversationsById.put(conversation.id(), conversation);
        return conversation;
    }

    @Override
    public ChatMessage saveChatMessage(ChatMessage message) {
        chatMessagesByConversationId.compute(message.conversationId(), (key, existing) -> {
            List<ChatMessage> updated = new ArrayList<>(existing == null ? List.of() : existing);
            updated.add(message);
            return updated;
        });
        return message;
    }

    @Override
    public List<ChatConversation> findChatConversationsByStudyId(String studyId) {
        return chatConversationsById.values().stream()
            .filter(conversation -> conversation.studyId().equals(studyId))
            .sorted(Comparator.comparing(ChatConversation::createdAt).reversed())
            .toList();
    }

    @Override
    public Optional<ChatConversation> findChatConversationById(String conversationId) {
        return Optional.ofNullable(chatConversationsById.get(conversationId));
    }

    @Override
    public List<ChatMessage> findChatMessagesByConversationId(String conversationId) {
        return chatMessagesByConversationId.getOrDefault(conversationId, List.of());
    }

    @Override
    public void saveChatTurn(ChatConversation conversationToCreate, ChatMessage userMessage, ChatMessage assistantMessage) {
        if (conversationToCreate != null) saveChatConversation(conversationToCreate);
        saveChatMessage(userMessage);
        saveChatMessage(assistantMessage);
    }
}
