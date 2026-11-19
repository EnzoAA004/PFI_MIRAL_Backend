package ar.edu.uade.pfi.backend.repository;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiDiscLevelReview;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegionCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRunReview;
import ar.edu.uade.pfi.backend.domain.ChatConversation;
import ar.edu.uade.pfi.backend.domain.ChatMessage;
import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.ProfessionalFeedbackCandidate;
import ar.edu.uade.pfi.backend.domain.ProfessionalFeedbackStatusChange;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.ReviewerAnnotation;
import ar.edu.uade.pfi.backend.domain.RunReview;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface StudyRepository {
    Study saveStudy(Study study);

    InputResource saveInput(InputResource input);

    StudyRun saveRun(StudyRun run);

    List<Study> findAllStudies();

    Optional<Study> findStudyByCaseId(String caseId);

    /** Atomically assigns the owner once. Existing/legacy studies are never claimed by a later caller. */
    default boolean claimStudyOwner(String caseId, String principalId) { return false; }

    default boolean hasStudyAccess(String caseId, String principalId, boolean write) { return false; }

    default boolean isStudyOwner(String caseId, String principalId) { return false; }

    default void grantStudyAccess(String caseId, String principalId, String permission, String grantedBy) { throw new UnsupportedOperationException("Study access grants are not supported"); }

    default List<String> findAccessibleCaseIds(String principalId) { return List.of(); }

    default Optional<String> resolveStudyCaseId(String resourceId) { return Optional.empty(); }

    List<Study> findStudiesBySubjectRef(String subjectRef);

    List<Study> findStudiesByPatientId(String patientId);

    Optional<Study> updatePatientIfExpected(String caseId, String targetPatientId, String expectedPatientId);

    List<InputResource> findInputsByStudyId(String studyId);

    List<StudyRun> findRunsByStudyId(String studyId);

    Optional<StudyRun> findLatestRunByStudyId(String studyId);

    Optional<StudyRun> findRunByMultiplanarRunId(String multiplanarRunId);

    Optional<StudyRun> findRunByTraceId(String traceId);

    List<RunArtifact> findArtifactsByRunId(String studyRunId);

    Optional<RunArtifact> findArtifactByRunPlaneAndName(String runId, String plane, String assetName);

    RunArtifact updateArtifactStorage(String artifactId, String storageStatus, String storageKind, Long sizeBytes, String sha256);

    /**
     * Overwrites the persisted metricsSnapshot for a run. Used to downgrade a
     * run's threeD to a blocked state after the workspace mesh asset failed
     * validation/storage post-persistence — never to fabricate new data.
     */
    StudyRun updateRunMetricsSnapshot(String multiplanarRunId, Map<String, Object> metricsSnapshot);

    RunReview saveReview(String multiplanarRunId, String reviewStatus, String reviewer, Instant reviewedAt, String comments, List<MeasurementCorrection> corrections);

    RunReview saveReview(String multiplanarRunId, String reviewStatus, String reviewer, Instant reviewedAt, String comments, List<MeasurementCorrection> corrections, DomainAuditEvent auditEvent);

    Optional<RunReview> findReviewByMultiplanarRunId(String multiplanarRunId);

    List<MeasurementCorrection> findCorrectionsByStudyRunId(String studyRunId);

    /**
     * Replaces the reviewer's annotations for a run with the given list.
     *
     * <p>Replace and not append: the client owns the full set it is editing, and an
     * append-only endpoint would make deleting an annotation impossible without a
     * second call that could half-apply.
     */
    List<ReviewerAnnotation> replaceAnnotations(String multiplanarRunId, List<ReviewerAnnotation> annotations);

    List<ReviewerAnnotation> findAnnotationsByRunId(String multiplanarRunId);

    DomainAuditEvent saveAuditEvent(DomainAuditEvent event);

    List<DomainAuditEvent> findAuditEventsByTraceId(String traceId);

    List<DomainAuditEvent> findAuditEventsByEntityId(String entityId);

    List<DomainAuditEvent> findAuditEventsByStudyId(String studyId);

    // ---- Stage 76: pfi.ai-product-result.v1 structured persistence ----

    /**
     * Persists one product-analysis run together with its disc-level results and measurements in
     * a single transaction (stage 76-J) — never a half-persisted run. Each call creates a NEW
     * run; there is no upsert-by-study semantics (stage 76-I/76-V: a Study can have many runs,
     * each with its own identity).
     */
    AiProductAnalysisRun saveAiProductAnalysisRun(AiProductAnalysisRun run, List<AiDiscLevelResult> discLevels, List<AiMeasurementResult> measurements);

    List<AiProductAnalysisRun> findAiProductAnalysisRunsByStudyId(String studyId);

    Optional<AiProductAnalysisRun> findAiProductAnalysisRunById(String id);

    List<AiDiscLevelResult> findAiDiscLevelResultsByAnalysisRunId(String analysisRunId);

    List<AiMeasurementResult> findAiMeasurementResultsByAnalysisRunId(String analysisRunId);

    // ---- Stage 78: professional review / correction workflow ----

    /**
     * Insert-or-update: exactly one review row per run. Never appends a second row for the same
     * {@code analysisRunId} — the profession's *current* decision, not a history (that lives in
     * {@code domain_audit_events} via the caller).
     */
    AiProductAnalysisRunReview upsertAiProductAnalysisRunReview(String analysisRunId, String reviewStatus, String reviewComment, String reviewedBy, Instant reviewedAt);

    Optional<AiProductAnalysisRunReview> findAiProductAnalysisRunReview(String analysisRunId);

    AiDiscLevelReview upsertAiDiscLevelReview(String discLevelResultId, String reviewStatus, String reviewComment, String reviewedBy, Instant reviewedAt);

    Optional<AiDiscLevelReview> findAiDiscLevelReview(String discLevelResultId);

    List<AiDiscLevelReview> findAiDiscLevelReviewsByAnalysisRunId(String analysisRunId);

    /**
     * Always inserts a new row — {@code domain_ai_measurement_results} is never updated by a
     * correction (stage 76's immutability guarantee). The "current" corrected value for a
     * measurement/field pair is the latest row by {@code createdAt}.
     */
    AiMeasurementCorrection saveAiMeasurementCorrection(AiMeasurementCorrection correction);

    List<AiMeasurementCorrection> findAiMeasurementCorrectionsByAnalysisRunId(String analysisRunId);

    // ---- EXT-04B2: visual evidence regions ----

    /**
     * Always inserts a new row — {@code domain_ai_evidence_regions} is a root that is never
     * updated once created (same immutability guarantee as {@code AiMeasurementResult}).
     */
    AiEvidenceRegion saveAiEvidenceRegion(AiEvidenceRegion region);

    List<AiEvidenceRegion> findAiEvidenceRegionsByAnalysisRunId(String analysisRunId);

    /**
     * Always inserts a new row — {@code domain_ai_evidence_regions} is never updated by a
     * correction. The "current" corrected geometry for a region is the latest row by
     * {@code createdAt}.
     */
    AiEvidenceRegionCorrection saveAiEvidenceRegionCorrection(AiEvidenceRegionCorrection correction);

    List<AiEvidenceRegionCorrection> findAiEvidenceRegionCorrectionsByAnalysisRunId(String analysisRunId);

    // ---- EXT-07D: professional feedback capture (never training) ----

    /**
     * Idempotent at the caller's discretion via {@link #existsProfessionalFeedbackCandidate}
     * (also enforced by a DB-level {@code UNIQUE(source, correctionId)} constraint) — capturing
     * the same correction/new-finding twice must never duplicate a row.
     */
    ProfessionalFeedbackCandidate saveProfessionalFeedbackCandidate(ProfessionalFeedbackCandidate candidate);

    boolean existsProfessionalFeedbackCandidate(String source, String correctionId);

    List<ProfessionalFeedbackCandidate> findProfessionalFeedbackCandidatesByCaseId(String caseId);

    Optional<ProfessionalFeedbackCandidate> findProfessionalFeedbackCandidateById(String id);

    /**
     * EXT-05: records one curation decision — updates the candidate's current
     * {@code status}/{@code curatorId}/{@code curatedAt}/{@code exclusionReason} AND appends a
     * row to the separate append-only history table. Every other field of a captured candidate
     * (its AI-original/professional values, provenance, etc.) is immutable.
     */
    ProfessionalFeedbackCandidate recordFeedbackCandidateStatusChange(String id, String toStatus, String curatorId, String reason, String note, java.time.Instant changedAt);

    List<ProfessionalFeedbackStatusChange> findFeedbackCandidateStatusChanges(String candidateId);

    // ---- Stage 80: contextual chat ----

    /** Always inserts a new conversation — a Study can have many (80-U), never overwritten. */
    ChatConversation saveChatConversation(ChatConversation conversation);

    ChatMessage saveChatMessage(ChatMessage message);

    List<ChatConversation> findChatConversationsByStudyId(String studyId);

    Optional<ChatConversation> findChatConversationById(String conversationId);

    List<ChatMessage> findChatMessagesByConversationId(String conversationId);

    /**
     * Stage 81-C/81-M: persists one chat turn (one USER message + its ASSISTANT reply)
     * atomically. When {@code conversationToCreate} is non-null, the conversation row is
     * inserted first in the same transaction (a brand-new conversation); when {@code null}, both
     * messages are appended to an already-existing conversation. Fixes a Stage-80 gap where
     * {@code saveChatConversation}/{@code saveChatMessage} were called as three independent
     * writes — a failure between them could leave an orphan conversation with only a user turn
     * and no assistant reply.
     */
    void saveChatTurn(ChatConversation conversationToCreate, ChatMessage userMessage, ChatMessage assistantMessage);
}
