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

    /** Asigna el owner de forma atómica una sola vez. Los estudios existentes/legacy nunca son reclamados por un llamante posterior. */
    default boolean claimStudyOwner(String caseId, String principalId) { return false; }

    default boolean hasStudyAccess(String caseId, String principalId, boolean write) { return false; }

    default boolean isStudyOwner(String caseId, String principalId) { return false; }

    default void grantStudyAccess(String caseId, String principalId, String permission, String grantedBy) { throw new UnsupportedOperationException("Study access grants are not supported"); }

    default List<String> findAccessibleCaseIds(String principalId) { return List.of(); }

    default Optional<String> resolveStudyCaseId(String resourceId) { return Optional.empty(); }

    /** Registra el resultado de un pipeline contra un caso ya persistido sin reasignar los IDs existentes. */
    default boolean registerAiPipelineRunResource(String runId, String caseId) { return false; }

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
     * Sobrescribe el metricsSnapshot persistido de una corrida. Se usa para degradar el
     * threeD de una corrida a un estado bloqueado luego de que el asset de malla del workspace falló la
     * validación/almacenamiento posterior a la persistencia — nunca para fabricar datos nuevos.
     */
    StudyRun updateRunMetricsSnapshot(String multiplanarRunId, Map<String, Object> metricsSnapshot);

    RunReview saveReview(String multiplanarRunId, String reviewStatus, String reviewer, Instant reviewedAt, String comments, List<MeasurementCorrection> corrections);

    RunReview saveReview(String multiplanarRunId, String reviewStatus, String reviewer, Instant reviewedAt, String comments, List<MeasurementCorrection> corrections, DomainAuditEvent auditEvent);

    Optional<RunReview> findReviewByMultiplanarRunId(String multiplanarRunId);

    List<MeasurementCorrection> findCorrectionsByStudyRunId(String studyRunId);

    /**
     * Reemplaza las anotaciones del revisor de una corrida por la lista dada.
     *
     * <p>Reemplazar y no agregar: el cliente es dueño del conjunto completo que está editando, y un
     * endpoint solo de append haría imposible borrar una anotación sin una
     * segunda llamada que podría aplicarse a medias.
     */
    List<ReviewerAnnotation> replaceAnnotations(String multiplanarRunId, List<ReviewerAnnotation> annotations);

    List<ReviewerAnnotation> findAnnotationsByRunId(String multiplanarRunId);

    /** Borrador del preinforme de una corrida, si existe. */
    default java.util.Optional<ar.edu.uade.pfi.backend.domain.ReportDraft> findReportDraft(String studyId, String runId) {
        throw new UnsupportedOperationException("report drafts");
    }

    /**
     * Crea (expectedVersion == null) o actualiza (expectedVersion == version vigente) el borrador de la corrida. Una version
     * que no coincide es un conflicto: lo resuelve quien llama, nunca se pisa en silencio.
     */
    default ar.edu.uade.pfi.backend.domain.ReportDraft saveReportDraft(ar.edu.uade.pfi.backend.domain.ReportDraft draft, Integer expectedVersion) {
        throw new UnsupportedOperationException("report drafts");
    }

    /** Decisiones del profesional sobre los hallazgos de una corrida (vacio si no decidio ninguna). */
    default java.util.List<ar.edu.uade.pfi.backend.domain.FindingDecision> findFindingDecisions(String studyId, String runId) {
        throw new UnsupportedOperationException("finding decisions");
    }

    /** Crea o reemplaza la decision de un hallazgo. */
    default ar.edu.uade.pfi.backend.domain.FindingDecision saveFindingDecision(ar.edu.uade.pfi.backend.domain.FindingDecision decision) {
        throw new UnsupportedOperationException("finding decisions");
    }

    /** Quita la decision (el hallazgo vuelve a "sin revisar"). */
    default void deleteFindingDecision(String studyId, String runId, String findingKey) {
        throw new UnsupportedOperationException("finding decisions");
    }

    DomainAuditEvent saveAuditEvent(DomainAuditEvent event);

    List<DomainAuditEvent> findAuditEventsByTraceId(String traceId);

    List<DomainAuditEvent> findAuditEventsByEntityId(String entityId);

    List<DomainAuditEvent> findAuditEventsByStudyId(String studyId);

    // ---- Etapa 76: persistencia estructurada de pfi.ai-product-result.v1 ----

    /**
     * Persiste una corrida de product-analysis junto con los resultados de sus niveles de disco y sus mediciones en
     * una única transacción (etapa 76-J) — nunca una corrida persistida a medias. Cada llamada crea una corrida NUEVA;
     * no hay semántica de upsert por estudio (etapa 76-I/76-V: un Study puede tener muchas corridas,
     * cada una con su propia identidad).
     */
    AiProductAnalysisRun saveAiProductAnalysisRun(AiProductAnalysisRun run, List<AiDiscLevelResult> discLevels, List<AiMeasurementResult> measurements);

    List<AiProductAnalysisRun> findAiProductAnalysisRunsByStudyId(String studyId);

    Optional<AiProductAnalysisRun> findAiProductAnalysisRunById(String id);

    List<AiDiscLevelResult> findAiDiscLevelResultsByAnalysisRunId(String analysisRunId);

    List<AiMeasurementResult> findAiMeasurementResultsByAnalysisRunId(String analysisRunId);

    // ---- Etapa 78: flujo de revisión / corrección profesional ----

    /**
     * Insert-or-update: exactamente una fila de revisión por corrida. Nunca agrega una segunda fila para el mismo
     * {@code analysisRunId} — la decisión *actual* del profesional, no un historial (ese vive en
     * {@code domain_audit_events} a través de quien llama).
     */
    AiProductAnalysisRunReview upsertAiProductAnalysisRunReview(String analysisRunId, String reviewStatus, String reviewComment, String reviewedBy, Instant reviewedAt);

    Optional<AiProductAnalysisRunReview> findAiProductAnalysisRunReview(String analysisRunId);

    AiDiscLevelReview upsertAiDiscLevelReview(String discLevelResultId, String reviewStatus, String reviewComment, String reviewedBy, Instant reviewedAt);

    Optional<AiDiscLevelReview> findAiDiscLevelReview(String discLevelResultId);

    List<AiDiscLevelReview> findAiDiscLevelReviewsByAnalysisRunId(String analysisRunId);

    /**
     * Siempre inserta una fila nueva — {@code domain_ai_measurement_results} nunca se actualiza por una
     * corrección (la garantía de inmutabilidad de la etapa 76). El valor corregido "actual" de un
     * par medición/campo es la última fila por {@code createdAt}.
     */
    AiMeasurementCorrection saveAiMeasurementCorrection(AiMeasurementCorrection correction);

    List<AiMeasurementCorrection> findAiMeasurementCorrectionsByAnalysisRunId(String analysisRunId);

    // ---- EXT-04B2: regiones de evidencia visual ----

    /**
     * Siempre inserta una fila nueva — {@code domain_ai_evidence_regions} es una raíz que nunca se
     * actualiza una vez creada (la misma garantía de inmutabilidad que {@code AiMeasurementResult}).
     */
    AiEvidenceRegion saveAiEvidenceRegion(AiEvidenceRegion region);

    List<AiEvidenceRegion> findAiEvidenceRegionsByAnalysisRunId(String analysisRunId);

    /**
     * Siempre inserta una fila nueva — {@code domain_ai_evidence_regions} nunca se actualiza por una
     * corrección. La geometría corregida "actual" de una región es la última fila por
     * {@code createdAt}.
     */
    AiEvidenceRegionCorrection saveAiEvidenceRegionCorrection(AiEvidenceRegionCorrection correction);

    List<AiEvidenceRegionCorrection> findAiEvidenceRegionCorrectionsByAnalysisRunId(String analysisRunId);

    // ---- EXT-07D: captura de feedback profesional (nunca entrenamiento) ----

    /**
     * Idempotente a criterio de quien llama vía {@link #existsProfessionalFeedbackCandidate}
     * (también reforzado por un constraint {@code UNIQUE(source, correctionId)} a nivel de DB) — capturar
     * la misma corrección/nuevo hallazgo dos veces nunca debe duplicar una fila.
     */
    ProfessionalFeedbackCandidate saveProfessionalFeedbackCandidate(ProfessionalFeedbackCandidate candidate);

    boolean existsProfessionalFeedbackCandidate(String source, String correctionId);

    List<ProfessionalFeedbackCandidate> findProfessionalFeedbackCandidatesByCaseId(String caseId);

    Optional<ProfessionalFeedbackCandidate> findProfessionalFeedbackCandidateById(String id);

    /**
     * EXT-05: registra una decisión de curación — actualiza {@code status}/{@code curatorId}/{@code curatedAt}/{@code exclusionReason}
     * actuales del candidato Y agrega una
     * fila a la tabla de historial append-only separada. Todo otro campo de un candidato capturado
     * (sus valores originales de la IA/profesionales, procedencia, etc.) es inmutable.
     */
    ProfessionalFeedbackCandidate recordFeedbackCandidateStatusChange(String id, String toStatus, String curatorId, String reason, String note, java.time.Instant changedAt);

    List<ProfessionalFeedbackStatusChange> findFeedbackCandidateStatusChanges(String candidateId);

    // ---- Etapa 80: chat contextual ----

    /** Siempre inserta una conversación nueva — un Study puede tener muchas (80-U), nunca se sobrescribe. */
    ChatConversation saveChatConversation(ChatConversation conversation);

    ChatMessage saveChatMessage(ChatMessage message);

    List<ChatConversation> findChatConversationsByStudyId(String studyId);

    Optional<ChatConversation> findChatConversationById(String conversationId);

    List<ChatMessage> findChatMessagesByConversationId(String conversationId);

    /**
     * Etapa 81-C/81-M: persiste un turno de chat (un mensaje USER + su respuesta ASSISTANT)
     * de forma atómica. Cuando {@code conversationToCreate} no es null, la fila de conversación se
     * inserta primero en la misma transacción (una conversación nueva); cuando es {@code null}, ambos
     * mensajes se agregan a una conversación ya existente. Corrige una brecha de la Etapa 80 donde
     * {@code saveChatConversation}/{@code saveChatMessage} se llamaban como tres
     * escrituras independientes — una falla entre ellas podía dejar una conversación huérfana con solo un turno de usuario
     * y sin respuesta del assistant.
     */
    void saveChatTurn(ChatConversation conversationToCreate, ChatMessage userMessage, ChatMessage assistantMessage);
}
