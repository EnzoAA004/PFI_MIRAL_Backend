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
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.ReviewerAnnotation;
import ar.edu.uade.pfi.backend.domain.RunReview;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "pfi.persistence.mode", havingValue = "postgres")
public class PostgresStudyRepository implements StudyRepository {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> JSON_MAP_LIST = new TypeReference<>() {};
    private static final TypeReference<List<String>> JSON_STRING_LIST = new TypeReference<>() {};

    private final String jdbcUrl;
    private final ObjectMapper objectMapper;

    @Autowired
    public PostgresStudyRepository(
        ObjectMapper objectMapper,
        @Value("${pfi.database.url:${DATABASE_URL:}}") String databaseUrl
    ) {
        this(objectMapper, databaseUrl, true);
    }

    public PostgresStudyRepository(ObjectMapper objectMapper, String databaseUrl, boolean applyMigrations) {
        this.objectMapper = objectMapper;
        this.jdbcUrl = toJdbcUrl(databaseUrl == null ? "" : databaseUrl.trim());
        if (applyMigrations) {
            try (Connection connection = connection()) {
                new SqlMigrationRunner(SqlMigrationRunner.DEFAULT_MIGRATIONS_DIRECTORY).apply(connection);
            } catch (Exception ex) {
                throw new IllegalStateException("Could not initialize PostgreSQL study repository", ex);
            }
        }
    }

    @Override
    public Study saveStudy(Study study) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_studies(
                id, case_id, status, subject_ref, study_date, modality, description, review_priority, created_at, updated_at
            )
            VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (case_id) DO UPDATE SET
              status = EXCLUDED.status,
              subject_ref = EXCLUDED.subject_ref,
              study_date = EXCLUDED.study_date,
              modality = EXCLUDED.modality,
              description = EXCLUDED.description,
              review_priority = EXCLUDED.review_priority,
              updated_at = EXCLUDED.updated_at
            """)) {
            statement.setString(1, study.id());
            statement.setString(2, study.caseId());
            statement.setString(3, study.status());
            statement.setString(4, study.subjectRef());
            statement.setDate(5, study.studyDate() == null ? null : Date.valueOf(study.studyDate()));
            statement.setString(6, study.modality());
            statement.setString(7, study.description());
            statement.setString(8, study.reviewPriority());
            statement.setTimestamp(9, Timestamp.from(study.createdAt()));
            statement.setTimestamp(10, Timestamp.from(study.updatedAt()));
            statement.executeUpdate();
            return study;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save study", ex);
        }
    }

    @Override
    public InputResource saveInput(InputResource input) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_input_resources(
                id, study_id, plane, input_id, format, size_bytes,
                description, weighting, slice_count, multiplanar, derived, analyzable, created_at)
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (input_id) DO UPDATE SET
              format = EXCLUDED.format,
              size_bytes = EXCLUDED.size_bytes,
              description = EXCLUDED.description,
              weighting = EXCLUDED.weighting,
              slice_count = EXCLUDED.slice_count,
              multiplanar = EXCLUDED.multiplanar,
              derived = EXCLUDED.derived,
              analyzable = EXCLUDED.analyzable
            """)) {
            statement.setString(1, input.id());
            statement.setString(2, input.studyId());
            statement.setString(3, input.plane());
            statement.setString(4, input.inputId());
            statement.setString(5, input.format());
            statement.setLong(6, input.size());
            statement.setString(7, input.description());
            statement.setString(8, input.weighting());
            statement.setInt(9, input.sliceCount());
            statement.setBoolean(10, input.multiplanar());
            statement.setBoolean(11, input.derived());
            statement.setBoolean(12, input.analyzable());
            statement.setTimestamp(13, Timestamp.from(input.createdAt()));
            statement.executeUpdate();
            return input;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save input resource", ex);
        }
    }

    @Override
    public StudyRun saveRun(StudyRun run) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                String persistedStudyRunId = upsertRun(connection, run);
                List<RunArtifact> persistedArtifacts = normalizeArtifacts(run.artifacts(), persistedStudyRunId);
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM domain_run_artifacts WHERE study_run_id = ?::uuid")) {
                    delete.setString(1, persistedStudyRunId);
                    delete.executeUpdate();
                }
                for (RunArtifact artifact : persistedArtifacts) saveArtifact(connection, artifact);
                connection.commit();
                return withPersistedId(run, persistedStudyRunId, persistedArtifacts);
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save study run", ex);
        }
    }

    private String upsertRun(Connection connection, StudyRun run) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_study_runs(
                id, study_id, multiplanar_run_id, trace_id, requested_inference_mode, effective_inference_mode,
                sagittal_model_key, axial_model_key, sagittal_artifact_hash, axial_artifact_hash,
                sagittal_run_id, axial_run_id, assets, metrics_snapshot, status,
                review_status, reviewer, reviewed_at, comments, created_at, updated_at
            )
            VALUES (
                ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?,
                ?, ?, ?, ?, ?, ?
            )
            ON CONFLICT (multiplanar_run_id) DO UPDATE SET
              trace_id = EXCLUDED.trace_id,
              requested_inference_mode = EXCLUDED.requested_inference_mode,
              effective_inference_mode = EXCLUDED.effective_inference_mode,
              sagittal_model_key = EXCLUDED.sagittal_model_key,
              axial_model_key = EXCLUDED.axial_model_key,
              sagittal_artifact_hash = EXCLUDED.sagittal_artifact_hash,
              axial_artifact_hash = EXCLUDED.axial_artifact_hash,
              sagittal_run_id = EXCLUDED.sagittal_run_id,
              axial_run_id = EXCLUDED.axial_run_id,
              assets = EXCLUDED.assets,
              metrics_snapshot = EXCLUDED.metrics_snapshot,
              status = EXCLUDED.status,
              review_status = EXCLUDED.review_status,
              reviewer = EXCLUDED.reviewer,
              reviewed_at = EXCLUDED.reviewed_at,
              comments = EXCLUDED.comments,
              updated_at = EXCLUDED.updated_at
            RETURNING id
            """)) {
            statement.setString(1, run.id());
            statement.setString(2, run.studyId());
            statement.setString(3, run.multiplanarRunId());
            statement.setString(4, run.traceId());
            statement.setString(5, run.requestedInferenceMode());
            statement.setString(6, run.effectiveInferenceMode());
            statement.setString(7, run.sagittalModelKey());
            statement.setString(8, run.axialModelKey());
            statement.setString(9, run.sagittalArtifactHash());
            statement.setString(10, run.axialArtifactHash());
            statement.setString(11, run.sagittalRunId());
            statement.setString(12, run.axialRunId());
            statement.setString(13, objectMapper.writeValueAsString(run.assets()));
            statement.setString(14, objectMapper.writeValueAsString(run.metricsSnapshot()));
            statement.setString(15, run.status());
            statement.setString(16, run.reviewStatus());
            statement.setString(17, run.reviewer());
            statement.setTimestamp(18, run.reviewedAt() == null ? null : Timestamp.from(run.reviewedAt()));
            statement.setString(19, run.comments());
            statement.setTimestamp(20, Timestamp.from(run.createdAt()));
            statement.setTimestamp(21, Timestamp.from(run.updatedAt()));
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("Could not obtain persisted study run id");
                return rs.getObject("id", UUID.class).toString();
            }
        }
    }

    private List<RunArtifact> normalizeArtifacts(List<RunArtifact> artifacts, String persistedStudyRunId) {
        if (artifacts == null || artifacts.isEmpty()) return List.of();
        List<RunArtifact> normalized = new ArrayList<>();
        for (RunArtifact artifact : artifacts) {
            normalized.add(new RunArtifact(
                artifact.id(),
                persistedStudyRunId,
                artifact.runId(),
                artifact.plane(),
                artifact.assetName(),
                artifact.contentType(),
                artifact.artifactRef(),
                artifact.createdAt(),
                artifact.storageStatus(),
                artifact.storageKind(),
                artifact.sizeBytes(),
                artifact.sha256()
            ));
        }
        return normalized;
    }

    private StudyRun withPersistedId(StudyRun run, String persistedStudyRunId, List<RunArtifact> artifacts) {
        return new StudyRun(
            persistedStudyRunId,
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
            artifacts,
            run.status(),
            run.reviewStatus(),
            run.reviewer(),
            run.reviewedAt(),
            run.comments(),
            run.createdAt(),
            run.updatedAt()
        );
    }

    @Override
    public List<Study> findAllStudies() {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, case_id, status, subject_ref, study_date, modality, description, review_priority, created_at, updated_at
            FROM domain_studies
            ORDER BY updated_at DESC, created_at DESC
            """)) {
            try (ResultSet rs = statement.executeQuery()) {
                List<Study> studies = new ArrayList<>();
                while (rs.next()) studies.add(readStudy(rs));
                return studies;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not list studies", ex);
        }
    }

    @Override
    public Optional<Study> findStudyByCaseId(String caseId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, case_id, status, subject_ref, study_date, modality, description, review_priority, created_at, updated_at
            FROM domain_studies
            WHERE case_id = ?
            """)) {
            statement.setString(1, caseId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readStudy(rs));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find study", ex);
        }
    }

    @Override
    public List<Study> findStudiesBySubjectRef(String subjectRef) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, case_id, status, subject_ref, study_date, modality, description, review_priority, created_at, updated_at
            FROM domain_studies
            WHERE subject_ref IS NOT NULL AND lower(subject_ref) = lower(?)
            ORDER BY study_date DESC NULLS LAST, created_at DESC
            """)) {
            statement.setString(1, subjectRef);
            try (ResultSet rs = statement.executeQuery()) {
                List<Study> studies = new ArrayList<>();
                while (rs.next()) studies.add(readStudy(rs));
                return studies;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find studies by subject ref", ex);
        }
    }

    @Override
    public List<StudyRun> findRunsByStudyId(String studyId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, study_id, multiplanar_run_id, trace_id, requested_inference_mode, effective_inference_mode,
                   sagittal_model_key, axial_model_key, sagittal_artifact_hash, axial_artifact_hash,
                   sagittal_run_id, axial_run_id, assets, metrics_snapshot, status,
                   review_status, reviewer, reviewed_at, comments, created_at, updated_at
            FROM domain_study_runs
            WHERE study_id = ?::uuid
            ORDER BY created_at DESC, updated_at DESC
            """)) {
            statement.setString(1, studyId);
            try (ResultSet rs = statement.executeQuery()) {
                List<StudyRun> runs = new ArrayList<>();
                while (rs.next()) runs.add(readRun(connection, rs));
                return runs;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not list study runs", ex);
        }
    }

    @Override
    public Optional<StudyRun> findLatestRunByStudyId(String studyId) {
        List<StudyRun> runs = findRunsByStudyId(studyId);
        if (runs.isEmpty()) return Optional.empty();
        return Optional.of(runs.get(0));
    }

    @Override
    public List<MeasurementCorrection> findCorrectionsByStudyRunId(String studyRunId) {
        try (Connection connection = connection()) {
            return findCorrections(connection, studyRunId);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find measurement corrections", ex);
        }
    }

    @Override
    public List<ReviewerAnnotation> replaceAnnotations(String multiplanarRunId, List<ReviewerAnnotation> annotations) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                String studyRunId = studyRunIdFor(connection, multiplanarRunId);
                try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM domain_reviewer_annotations WHERE study_run_id = ?::uuid")) {
                    delete.setString(1, studyRunId);
                    delete.executeUpdate();
                }
                for (ReviewerAnnotation annotation : annotations) {
                    insertAnnotation(connection, studyRunId, annotation);
                }
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
            return List.copyOf(annotations);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not replace reviewer annotations", ex);
        }
    }

    @Override
    public List<ReviewerAnnotation> findAnnotationsByRunId(String multiplanarRunId) {
        try (Connection connection = connection()) {
            String studyRunId = studyRunIdFor(connection, multiplanarRunId);
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, study_run_id, scope, kind, measurement_kind, plane, series_id, slice_index, level,
                       points, value, unit, text, author, created_at
                FROM domain_reviewer_annotations
                WHERE study_run_id = ?::uuid
                ORDER BY created_at, id
                """)) {
                statement.setString(1, studyRunId);
                try (ResultSet rs = statement.executeQuery()) {
                    List<ReviewerAnnotation> annotations = new ArrayList<>();
                    while (rs.next()) {
                        annotations.add(readAnnotation(rs));
                    }
                    return annotations;
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find reviewer annotations", ex);
        }
    }

    private String studyRunIdFor(Connection connection, String multiplanarRunId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM domain_study_runs WHERE multiplanar_run_id = ?")) {
            statement.setString(1, multiplanarRunId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Corrida no encontrada: " + multiplanarRunId);
                return rs.getObject("id", UUID.class).toString();
            }
        }
    }

    private void insertAnnotation(Connection connection, String studyRunId, ReviewerAnnotation annotation) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_reviewer_annotations(
                id, study_run_id, scope, kind, measurement_kind, plane, series_id, slice_index, level,
                points, value, unit, text, author, created_at)
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, annotation.id());
            statement.setString(2, studyRunId);
            statement.setString(3, annotation.scope());
            statement.setString(4, annotation.kind());
            statement.setString(5, annotation.measurementKind());
            statement.setString(6, annotation.plane());
            statement.setString(7, annotation.seriesId());
            if (annotation.sliceIndex() == null) statement.setNull(8, java.sql.Types.INTEGER);
            else statement.setInt(8, annotation.sliceIndex());
            statement.setString(9, annotation.level());
            statement.setString(10, objectMapper.writeValueAsString(annotation.points()));
            if (annotation.value() == null) statement.setNull(11, java.sql.Types.DOUBLE);
            else statement.setDouble(11, annotation.value());
            statement.setString(12, annotation.unit());
            statement.setString(13, annotation.text());
            statement.setString(14, annotation.author());
            statement.setTimestamp(15, Timestamp.from(annotation.createdAt()));
            statement.executeUpdate();
        }
    }

    private ReviewerAnnotation readAnnotation(ResultSet rs) throws Exception {
        int sliceIndex = rs.getInt("slice_index");
        boolean sliceIndexNull = rs.wasNull();
        double value = rs.getDouble("value");
        boolean valueNull = rs.wasNull();
        return new ReviewerAnnotation(
            rs.getObject("id", UUID.class).toString(),
            rs.getObject("study_run_id", UUID.class).toString(),
            rs.getString("scope"),
            rs.getString("kind"),
            rs.getString("measurement_kind"),
            rs.getString("plane"),
            rs.getString("series_id"),
            sliceIndexNull ? null : sliceIndex,
            rs.getString("level"),
            readJsonMapList(rs.getString("points")),
            valueNull ? null : value,
            rs.getString("unit"),
            rs.getString("text"),
            rs.getString("author"),
            rs.getTimestamp("created_at").toInstant()
        );
    }

    private List<Map<String, Object>> readJsonMapList(String json) throws Exception {
        if (json == null || json.isBlank()) return List.of();
        return objectMapper.readValue(json, JSON_MAP_LIST);
    }

    @Override
    public List<DomainAuditEvent> findAuditEventsByStudyId(String studyId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT DISTINCT event.id, event.actor, event.action, event.entity_id, event.trace_id, event.metadata, event.created_at
            FROM domain_audit_events event
            LEFT JOIN domain_study_runs run
              ON event.entity_id = run.multiplanar_run_id OR event.trace_id = run.trace_id
            WHERE run.study_id = ?::uuid OR event.entity_id = ?
            ORDER BY event.created_at, event.action
            """)) {
            statement.setString(1, studyId);
            statement.setString(2, studyId);
            try (ResultSet rs = statement.executeQuery()) {
                List<DomainAuditEvent> events = new ArrayList<>();
                while (rs.next()) events.add(readAuditEvent(rs));
                return events;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find study audit events", ex);
        }
    }

    @Override
    public List<InputResource> findInputsByStudyId(String studyId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, study_id, plane, input_id, format, size_bytes,
                   description, weighting, slice_count, multiplanar, derived, analyzable, created_at
            FROM domain_input_resources WHERE study_id = ?::uuid ORDER BY created_at
            """)) {
            statement.setString(1, studyId);
            try (ResultSet rs = statement.executeQuery()) {
                List<InputResource> inputs = new ArrayList<>();
                while (rs.next()) inputs.add(readInput(rs));
                return inputs;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find inputs", ex);
        }
    }

    @Override
    public Optional<StudyRun> findRunByMultiplanarRunId(String multiplanarRunId) {
        return findRun("multiplanar_run_id", multiplanarRunId);
    }

    @Override
    public Optional<StudyRun> findRunByTraceId(String traceId) {
        return findRun("trace_id", traceId);
    }

    @Override
    public List<RunArtifact> findArtifactsByRunId(String studyRunId) {
        try (Connection connection = connection()) {
            return findArtifacts(connection, studyRunId);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find artifacts", ex);
        }
    }

    @Override
    public RunReview saveReview(String multiplanarRunId, String reviewStatus, String reviewer, Instant reviewedAt, String comments, List<MeasurementCorrection> corrections) {
        return saveReview(multiplanarRunId, reviewStatus, reviewer, reviewedAt, comments, corrections, null);
    }

    @Override
    public RunReview saveReview(String multiplanarRunId, String reviewStatus, String reviewer, Instant reviewedAt, String comments, List<MeasurementCorrection> corrections, DomainAuditEvent auditEvent) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            StudyRun run;
            Instant updatedAt = reviewedAt == null ? Instant.now() : reviewedAt;
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE domain_study_runs
                    SET review_status = ?, reviewer = ?, reviewed_at = ?, comments = ?, updated_at = ?
                    WHERE multiplanar_run_id = ?
                    """)) {
                    statement.setString(1, reviewStatus);
                    statement.setString(2, reviewer);
                    statement.setTimestamp(3, reviewedAt == null ? null : Timestamp.from(reviewedAt));
                    statement.setString(4, comments);
                    statement.setTimestamp(5, Timestamp.from(updatedAt));
                    statement.setString(6, multiplanarRunId);
                    if (statement.executeUpdate() == 0) {
                        throw new IllegalArgumentException("run_not_found");
                    }
                }
                run = findRun(connection, "multiplanar_run_id", multiplanarRunId).orElseThrow();
                replaceCorrections(connection, run.id(), corrections);
                if (auditEvent != null) saveAuditEvent(connection, auditEvent);
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
            return new RunReview(multiplanarRunId, run.traceId(), reviewStatus, reviewer, reviewedAt, comments, findCorrections(connection, run.id()));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save run review", ex);
        }
    }

    @Override
    public Optional<RunReview> findReviewByMultiplanarRunId(String multiplanarRunId) {
        try (Connection connection = connection()) {
            Optional<StudyRun> run = findRun(connection, "multiplanar_run_id", multiplanarRunId);
            if (run.isEmpty()) return Optional.empty();
            StudyRun value = run.get();
            return Optional.of(new RunReview(
                value.multiplanarRunId(),
                value.traceId(),
                value.reviewStatus(),
                value.reviewer(),
                value.reviewedAt(),
                value.comments(),
                findCorrections(connection, value.id())
            ));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find run review", ex);
        }
    }

    @Override
    public DomainAuditEvent saveAuditEvent(DomainAuditEvent event) {
        try (Connection connection = connection()) {
            saveAuditEvent(connection, event);
            return event;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save audit event", ex);
        }
    }

    private void saveAuditEvent(Connection connection, DomainAuditEvent event) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_audit_events(id, actor, action, entity_id, trace_id, metadata, created_at)
            VALUES (?::uuid, ?, ?, ?, ?, ?::jsonb, ?)
            """)) {
            statement.setString(1, event.id());
            statement.setString(2, event.actor());
            statement.setString(3, event.action());
            statement.setString(4, event.entityId());
            statement.setString(5, event.traceId());
            statement.setString(6, objectMapper.writeValueAsString(event.metadata()));
            statement.setTimestamp(7, Timestamp.from(event.timestamp()));
            statement.executeUpdate();
        }
    }

    @Override
    public List<DomainAuditEvent> findAuditEventsByTraceId(String traceId) {
        return findAuditEvents("trace_id", traceId);
    }

    @Override
    public List<DomainAuditEvent> findAuditEventsByEntityId(String entityId) {
        return findAuditEvents("entity_id", entityId);
    }

    private Optional<StudyRun> findRun(String column, String value) {
        try (Connection connection = connection()) {
            return findRun(connection, column, value);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find study run", ex);
        }
    }

    private Optional<StudyRun> findRun(Connection connection, String column, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, study_id, multiplanar_run_id, trace_id, requested_inference_mode, effective_inference_mode,
                   sagittal_model_key, axial_model_key, sagittal_artifact_hash, axial_artifact_hash,
                   sagittal_run_id, axial_run_id, assets, metrics_snapshot, status,
                   review_status, reviewer, reviewed_at, comments, created_at, updated_at
            FROM domain_study_runs WHERE %s = ?
            """.formatted(column))) {
            statement.setString(1, value);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readRun(connection, rs));
            }
        }
    }

    private void replaceCorrections(Connection connection, String studyRunId, List<MeasurementCorrection> corrections) throws Exception {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM domain_review_corrections WHERE study_run_id = ?::uuid")) {
            delete.setString(1, studyRunId);
            delete.executeUpdate();
        }
        for (MeasurementCorrection correction : corrections) {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO domain_review_corrections(id, study_run_id, measurement_id, label, before_value, after_value, comment, created_at)
                VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """)) {
                statement.setString(1, correction.id());
                statement.setString(2, studyRunId);
                statement.setString(3, correction.measurementId());
                statement.setString(4, correction.label());
                statement.setString(5, objectMapper.writeValueAsString(correction.beforeValue()));
                statement.setString(6, objectMapper.writeValueAsString(correction.afterValue()));
                statement.setString(7, correction.comment());
                statement.setTimestamp(8, Timestamp.from(correction.createdAt()));
                statement.executeUpdate();
            }
        }
    }

    private List<MeasurementCorrection> findCorrections(Connection connection, String studyRunId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, study_run_id, measurement_id, label, before_value, after_value, comment, created_at
            FROM domain_review_corrections
            WHERE study_run_id = ?::uuid
            ORDER BY created_at, measurement_id
            """)) {
            statement.setString(1, studyRunId);
            try (ResultSet rs = statement.executeQuery()) {
                List<MeasurementCorrection> corrections = new ArrayList<>();
                while (rs.next()) {
                    corrections.add(new MeasurementCorrection(
                        rs.getObject("id", UUID.class).toString(),
                        rs.getObject("study_run_id", UUID.class).toString(),
                        rs.getString("measurement_id"),
                        rs.getString("label"),
                        readJsonMap(rs.getString("before_value")),
                        readJsonMap(rs.getString("after_value")),
                        rs.getString("comment"),
                        rs.getTimestamp("created_at").toInstant()
                    ));
                }
                return corrections;
            }
        }
    }

    private void saveArtifact(Connection connection, RunArtifact artifact) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_run_artifacts(
                id, study_run_id, run_id, plane, asset_name, content_type, artifact_ref, created_at,
                storage_status, storage_kind, size_bytes, sha256
            )
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, artifact.id());
            statement.setString(2, artifact.studyRunId());
            statement.setString(3, artifact.runId());
            statement.setString(4, artifact.plane());
            statement.setString(5, artifact.assetName());
            statement.setString(6, artifact.contentType());
            statement.setString(7, artifact.artifactRef());
            statement.setTimestamp(8, Timestamp.from(artifact.createdAt()));
            statement.setString(9, artifact.storageStatus());
            statement.setString(10, artifact.storageKind());
            if (artifact.sizeBytes() == null) statement.setObject(11, null);
            else statement.setLong(11, artifact.sizeBytes());
            statement.setString(12, artifact.sha256());
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<RunArtifact> findArtifactByRunPlaneAndName(String runId, String plane, String assetName) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, study_run_id, run_id, plane, asset_name, content_type, artifact_ref, created_at,
                   storage_status, storage_kind, size_bytes, sha256
            FROM domain_run_artifacts
            WHERE run_id = ? AND plane = ? AND asset_name = ?
            ORDER BY created_at DESC
            LIMIT 1
            """)) {
            statement.setString(1, runId);
            statement.setString(2, plane);
            statement.setString(3, assetName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(readArtifact(rs)) : Optional.empty();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find run artifact", ex);
        }
    }

    @Override
    public RunArtifact updateArtifactStorage(String artifactId, String storageStatus, String storageKind, Long sizeBytes, String sha256) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            UPDATE domain_run_artifacts
            SET storage_status = ?, storage_kind = ?, size_bytes = ?, sha256 = ?
            WHERE id = ?::uuid
            RETURNING id, study_run_id, run_id, plane, asset_name, content_type, artifact_ref, created_at,
                      storage_status, storage_kind, size_bytes, sha256
            """)) {
            statement.setString(1, storageStatus);
            statement.setString(2, storageKind);
            if (sizeBytes == null) statement.setObject(3, null);
            else statement.setLong(3, sizeBytes);
            statement.setString(4, sha256);
            statement.setString(5, artifactId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Artifact not found");
                return readArtifact(rs);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not update run artifact storage", ex);
        }
    }

    @Override
    public StudyRun updateRunMetricsSnapshot(String multiplanarRunId, Map<String, Object> metricsSnapshot) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            UPDATE domain_study_runs
            SET metrics_snapshot = ?::jsonb, updated_at = now()
            WHERE multiplanar_run_id = ?
            RETURNING id, study_id, multiplanar_run_id, trace_id, requested_inference_mode, effective_inference_mode,
                      sagittal_model_key, axial_model_key, sagittal_artifact_hash, axial_artifact_hash,
                      sagittal_run_id, axial_run_id, assets, metrics_snapshot, status,
                      review_status, reviewer, reviewed_at, comments, created_at, updated_at
            """)) {
            statement.setString(1, objectMapper.writeValueAsString(metricsSnapshot));
            statement.setString(2, multiplanarRunId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Run not found");
                return readRun(connection, rs);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not update run metrics snapshot", ex);
        }
    }

    private List<DomainAuditEvent> findAuditEvents(String column, String value) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, actor, action, entity_id, trace_id, metadata, created_at
            FROM domain_audit_events
            WHERE %s = ?
            ORDER BY created_at, action
            """.formatted(column))) {
            statement.setString(1, value);
            try (ResultSet rs = statement.executeQuery()) {
                List<DomainAuditEvent> events = new ArrayList<>();
                while (rs.next()) events.add(readAuditEvent(rs));
                return events;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find audit events", ex);
        }
    }

    private Study readStudy(ResultSet rs) throws Exception {
        Date studyDate = rs.getDate("study_date");
        return new Study(
            rs.getObject("id", UUID.class).toString(),
            rs.getString("case_id"),
            rs.getString("status"),
            rs.getString("subject_ref"),
            studyDate == null ? null : studyDate.toLocalDate(),
            rs.getString("modality"),
            rs.getString("description"),
            rs.getString("review_priority"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }

    private DomainAuditEvent readAuditEvent(ResultSet rs) throws Exception {
        return new DomainAuditEvent(
            rs.getObject("id", UUID.class).toString(),
            rs.getString("actor"),
            rs.getString("action"),
            rs.getString("entity_id"),
            rs.getString("trace_id"),
            rs.getTimestamp("created_at").toInstant(),
            readJsonMap(rs.getString("metadata"))
        );
    }

    private InputResource readInput(ResultSet rs) throws Exception {
        return new InputResource(
            rs.getObject("id", UUID.class).toString(),
            rs.getObject("study_id", UUID.class).toString(),
            rs.getString("plane"),
            rs.getString("input_id"),
            rs.getString("format"),
            rs.getLong("size_bytes"),
            rs.getString("description"),
            rs.getString("weighting"),
            rs.getInt("slice_count"),
            rs.getBoolean("multiplanar"),
            rs.getBoolean("derived"),
            rs.getBoolean("analyzable"),
            rs.getTimestamp("created_at").toInstant()
        );
    }

    private StudyRun readRun(Connection connection, ResultSet rs) throws Exception {
        String id = rs.getObject("id", UUID.class).toString();
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        return new StudyRun(
            id,
            rs.getObject("study_id", UUID.class).toString(),
            rs.getString("multiplanar_run_id"),
            rs.getString("trace_id"),
            rs.getString("requested_inference_mode"),
            rs.getString("effective_inference_mode"),
            rs.getString("sagittal_model_key"),
            rs.getString("axial_model_key"),
            rs.getString("sagittal_artifact_hash"),
            rs.getString("axial_artifact_hash"),
            rs.getString("sagittal_run_id"),
            rs.getString("axial_run_id"),
            readJsonMap(rs.getString("assets")),
            readJsonMap(rs.getString("metrics_snapshot")),
            findArtifacts(connection, id),
            rs.getString("status"),
            rs.getString("review_status"),
            rs.getString("reviewer"),
            reviewedAt == null ? null : reviewedAt.toInstant(),
            rs.getString("comments"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }

    private List<RunArtifact> findArtifacts(Connection connection, String studyRunId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, study_run_id, run_id, plane, asset_name, content_type, artifact_ref, created_at,
                   storage_status, storage_kind, size_bytes, sha256
            FROM domain_run_artifacts WHERE study_run_id = ?::uuid ORDER BY plane, asset_name
            """)) {
            statement.setString(1, studyRunId);
            try (ResultSet rs = statement.executeQuery()) {
                List<RunArtifact> artifacts = new ArrayList<>();
                while (rs.next()) {
                    artifacts.add(readArtifact(rs));
                }
                return artifacts;
            }
        }
    }

    private RunArtifact readArtifact(ResultSet rs) throws Exception {
        long sizeBytes = rs.getLong("size_bytes");
        return new RunArtifact(
            rs.getObject("id", UUID.class).toString(),
            rs.getObject("study_run_id", UUID.class).toString(),
            rs.getString("run_id"),
            rs.getString("plane"),
            rs.getString("asset_name"),
            rs.getString("content_type"),
            rs.getString("artifact_ref"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("storage_status"),
            rs.getString("storage_kind"),
            rs.wasNull() ? null : sizeBytes,
            rs.getString("sha256")
        );
    }

    private Map<String, Object> readJsonMap(String json) throws Exception {
        if (json == null || json.isBlank()) return Map.of();
        return objectMapper.readValue(json, JSON_MAP);
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(jdbcUrl);
    }

    private String toJdbcUrl(String databaseUrl) {
        if (databaseUrl.isBlank()) return "";
        if (databaseUrl.startsWith("jdbc:postgresql://")) return databaseUrl;
        if (!databaseUrl.startsWith("postgres://") && !databaseUrl.startsWith("postgresql://")) return databaseUrl;
        try {
            URI uri = URI.create(databaseUrl);
            String userInfo = uri.getUserInfo();
            String user = "";
            String password = "";
            if (userInfo != null) {
                String[] parts = userInfo.split(":", 2);
                user = parts.length > 0 ? parts[0] : "";
                password = parts.length > 1 ? parts[1] : "";
            }
            String query = "sslmode=require";
            if (!user.isBlank()) query += "&user=" + URLEncoder.encode(user, StandardCharsets.UTF_8);
            if (!password.isBlank()) query += "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            return "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath() + "?" + query;
        } catch (Exception ex) {
            return databaseUrl;
        }
    }

    // ---- Stage 76: pfi.ai-product-result.v1 structured persistence ----

    @Override
    public AiProductAnalysisRun saveAiProductAnalysisRun(AiProductAnalysisRun run, List<AiDiscLevelResult> discLevels, List<AiMeasurementResult> measurements) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                insertAiAnalysisRun(connection, run);
                for (AiDiscLevelResult level : discLevels == null ? List.<AiDiscLevelResult>of() : discLevels) {
                    insertAiDiscLevelResult(connection, level);
                }
                for (AiMeasurementResult measurement : measurements == null ? List.<AiMeasurementResult>of() : measurements) {
                    insertAiMeasurementResult(connection, measurement);
                }
                connection.commit();
                return run;
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save AI product analysis run", ex);
        }
    }

    private void insertAiAnalysisRun(Connection connection, AiProductAnalysisRun run) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_ai_analysis_runs(
                id, study_id, case_id, schema_version, release_id, status,
                human_review_required, not_clinical_diagnosis, autonomous_diagnosis,
                trace_id, sagittal_input_id, axial_input_id, warnings, raw_response,
                created_at, completed_at
            ) VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
            """)) {
            statement.setString(1, run.id());
            statement.setString(2, run.studyId());
            statement.setString(3, run.caseId());
            statement.setString(4, run.schemaVersion());
            statement.setString(5, run.releaseId());
            statement.setString(6, run.status());
            statement.setBoolean(7, run.humanReviewRequired());
            statement.setBoolean(8, run.notClinicalDiagnosis());
            statement.setBoolean(9, run.autonomousDiagnosis());
            statement.setString(10, run.traceId());
            statement.setString(11, run.sagittalInputId());
            statement.setString(12, run.axialInputId());
            statement.setString(13, objectMapper.writeValueAsString(run.warnings() == null ? List.of() : run.warnings()));
            statement.setString(14, run.rawResponseSanitized() == null ? null : objectMapper.writeValueAsString(run.rawResponseSanitized()));
            statement.setTimestamp(15, Timestamp.from(run.createdAt()));
            statement.setTimestamp(16, run.completedAt() == null ? null : Timestamp.from(run.completedAt()));
            statement.executeUpdate();
        }
    }

    private void insertAiDiscLevelResult(Connection connection, AiDiscLevelResult level) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_ai_disc_level_results(
                id, analysis_run_id, rank_index, level, localization_status, localization_source,
                localization_validated, abstention_preserved, axial_pairing_status, axial_pairing_validated,
                axial_pairing_experimental, axial_pairing_review_required, degenerative_findings_status,
                automatic_inference_enabled
            ) VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, level.id());
            statement.setString(2, level.analysisRunId());
            statement.setInt(3, level.rankIndex());
            statement.setString(4, level.level());
            statement.setString(5, level.localizationStatus());
            statement.setString(6, level.localizationSource());
            statement.setBoolean(7, level.localizationValidated());
            statement.setBoolean(8, level.abstentionPreserved());
            statement.setString(9, level.axialPairingStatus());
            statement.setBoolean(10, level.axialPairingValidated());
            statement.setBoolean(11, level.axialPairingExperimental());
            statement.setBoolean(12, level.axialPairingReviewRequired());
            statement.setString(13, level.degenerativeFindingsStatus());
            statement.setBoolean(14, level.automaticInferenceEnabled());
            statement.executeUpdate();
        }
    }

    private void insertAiMeasurementResult(Connection connection, AiMeasurementResult measurement) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_ai_measurement_results(
                id, analysis_run_id, disc_level_result_id, plane, level, structure,
                area_mm2, major_axis_mm, minor_axis_mm, review_required
            ) VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, measurement.id());
            statement.setString(2, measurement.analysisRunId());
            statement.setString(3, measurement.discLevelResultId());
            statement.setString(4, measurement.plane());
            statement.setString(5, measurement.level());
            statement.setString(6, measurement.structure());
            setNullableDouble(statement, 7, measurement.areaMm2());
            setNullableDouble(statement, 8, measurement.majorAxisMm());
            setNullableDouble(statement, 9, measurement.minorAxisMm());
            statement.setBoolean(10, measurement.reviewRequired());
            statement.executeUpdate();
        }
    }

    private void setNullableDouble(PreparedStatement statement, int index, Double value) throws Exception {
        if (value == null) {
            statement.setNull(index, java.sql.Types.DOUBLE);
        } else {
            statement.setDouble(index, value);
        }
    }

    @Override
    public List<AiProductAnalysisRun> findAiProductAnalysisRunsByStudyId(String studyId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, study_id, case_id, schema_version, release_id, status,
                   human_review_required, not_clinical_diagnosis, autonomous_diagnosis,
                   trace_id, sagittal_input_id, axial_input_id, warnings, raw_response,
                   created_at, completed_at
            FROM domain_ai_analysis_runs WHERE study_id = ?::uuid ORDER BY created_at DESC
            """)) {
            statement.setString(1, studyId);
            try (ResultSet rs = statement.executeQuery()) {
                List<AiProductAnalysisRun> runs = new ArrayList<>();
                while (rs.next()) runs.add(readAiProductAnalysisRun(rs));
                return runs;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not list AI product analysis runs", ex);
        }
    }

    @Override
    public Optional<AiProductAnalysisRun> findAiProductAnalysisRunById(String id) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, study_id, case_id, schema_version, release_id, status,
                   human_review_required, not_clinical_diagnosis, autonomous_diagnosis,
                   trace_id, sagittal_input_id, axial_input_id, warnings, raw_response,
                   created_at, completed_at
            FROM domain_ai_analysis_runs WHERE id = ?::uuid
            """)) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readAiProductAnalysisRun(rs));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find AI product analysis run", ex);
        }
    }

    private AiProductAnalysisRun readAiProductAnalysisRun(ResultSet rs) throws Exception {
        String warningsJson = rs.getString("warnings");
        String rawResponseJson = rs.getString("raw_response");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new AiProductAnalysisRun(
            rs.getObject("id", UUID.class).toString(),
            rs.getObject("study_id", UUID.class).toString(),
            rs.getString("case_id"),
            rs.getString("schema_version"),
            rs.getString("release_id"),
            rs.getString("status"),
            rs.getBoolean("human_review_required"),
            rs.getBoolean("not_clinical_diagnosis"),
            rs.getBoolean("autonomous_diagnosis"),
            rs.getString("trace_id"),
            rs.getString("sagittal_input_id"),
            rs.getString("axial_input_id"),
            warningsJson == null ? List.of() : objectMapper.readValue(warningsJson, JSON_STRING_LIST),
            rawResponseJson == null ? null : objectMapper.readValue(rawResponseJson, JSON_MAP),
            rs.getTimestamp("created_at").toInstant(),
            completedAt == null ? null : completedAt.toInstant()
        );
    }

    @Override
    public List<AiDiscLevelResult> findAiDiscLevelResultsByAnalysisRunId(String analysisRunId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, analysis_run_id, rank_index, level, localization_status, localization_source,
                   localization_validated, abstention_preserved, axial_pairing_status, axial_pairing_validated,
                   axial_pairing_experimental, axial_pairing_review_required, degenerative_findings_status,
                   automatic_inference_enabled
            FROM domain_ai_disc_level_results WHERE analysis_run_id = ?::uuid ORDER BY rank_index
            """)) {
            statement.setString(1, analysisRunId);
            try (ResultSet rs = statement.executeQuery()) {
                List<AiDiscLevelResult> results = new ArrayList<>();
                while (rs.next()) results.add(readAiDiscLevelResult(rs));
                return results;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not list AI disc level results", ex);
        }
    }

    private AiDiscLevelResult readAiDiscLevelResult(ResultSet rs) throws Exception {
        return new AiDiscLevelResult(
            rs.getObject("id", UUID.class).toString(),
            rs.getObject("analysis_run_id", UUID.class).toString(),
            rs.getInt("rank_index"),
            rs.getString("level"),
            rs.getString("localization_status"),
            rs.getString("localization_source"),
            rs.getBoolean("localization_validated"),
            rs.getBoolean("abstention_preserved"),
            rs.getString("axial_pairing_status"),
            rs.getBoolean("axial_pairing_validated"),
            rs.getBoolean("axial_pairing_experimental"),
            rs.getBoolean("axial_pairing_review_required"),
            rs.getString("degenerative_findings_status"),
            rs.getBoolean("automatic_inference_enabled")
        );
    }

    @Override
    public List<AiMeasurementResult> findAiMeasurementResultsByAnalysisRunId(String analysisRunId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, analysis_run_id, disc_level_result_id, plane, level, structure,
                   area_mm2, major_axis_mm, minor_axis_mm, review_required
            FROM domain_ai_measurement_results WHERE analysis_run_id = ?::uuid
            """)) {
            statement.setString(1, analysisRunId);
            try (ResultSet rs = statement.executeQuery()) {
                List<AiMeasurementResult> results = new ArrayList<>();
                while (rs.next()) results.add(readAiMeasurementResult(rs));
                return results;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not list AI measurement results", ex);
        }
    }

    private AiMeasurementResult readAiMeasurementResult(ResultSet rs) throws Exception {
        return new AiMeasurementResult(
            rs.getObject("id", UUID.class).toString(),
            rs.getObject("analysis_run_id", UUID.class).toString(),
            rs.getObject("disc_level_result_id", UUID.class).toString(),
            rs.getString("plane"),
            rs.getString("level"),
            rs.getString("structure"),
            getNullableDouble(rs, "area_mm2"),
            getNullableDouble(rs, "major_axis_mm"),
            getNullableDouble(rs, "minor_axis_mm"),
            rs.getBoolean("review_required")
        );
    }

    private Double getNullableDouble(ResultSet rs, String column) throws Exception {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    // ---- Stage 78: professional review / correction workflow ----

    @Override
    public AiProductAnalysisRunReview upsertAiProductAnalysisRunReview(String analysisRunId, String reviewStatus, String reviewComment, String reviewedBy, Instant reviewedAt) {
        String id = UUID.randomUUID().toString();
        Instant updatedAt = Instant.now();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_ai_analysis_run_reviews(id, analysis_run_id, review_status, review_comment, reviewed_by, reviewed_at, updated_at)
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?)
            ON CONFLICT (analysis_run_id) DO UPDATE SET
                review_status = EXCLUDED.review_status,
                review_comment = EXCLUDED.review_comment,
                reviewed_by = EXCLUDED.reviewed_by,
                reviewed_at = EXCLUDED.reviewed_at,
                updated_at = EXCLUDED.updated_at
            RETURNING id, updated_at
            """)) {
            statement.setString(1, id);
            statement.setString(2, analysisRunId);
            statement.setString(3, reviewStatus);
            statement.setString(4, reviewComment);
            statement.setString(5, reviewedBy);
            statement.setTimestamp(6, reviewedAt == null ? null : Timestamp.from(reviewedAt));
            statement.setTimestamp(7, Timestamp.from(updatedAt));
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return new AiProductAnalysisRunReview(
                    rs.getObject("id", UUID.class).toString(),
                    analysisRunId,
                    reviewStatus,
                    reviewComment,
                    reviewedBy,
                    reviewedAt,
                    rs.getTimestamp("updated_at").toInstant()
                );
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save AI product analysis run review", ex);
        }
    }

    @Override
    public Optional<AiProductAnalysisRunReview> findAiProductAnalysisRunReview(String analysisRunId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, analysis_run_id, review_status, review_comment, reviewed_by, reviewed_at, updated_at
            FROM domain_ai_analysis_run_reviews WHERE analysis_run_id = ?::uuid
            """)) {
            statement.setString(1, analysisRunId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readAiProductAnalysisRunReview(rs));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find AI product analysis run review", ex);
        }
    }

    private AiProductAnalysisRunReview readAiProductAnalysisRunReview(ResultSet rs) throws Exception {
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        return new AiProductAnalysisRunReview(
            rs.getObject("id", UUID.class).toString(),
            rs.getObject("analysis_run_id", UUID.class).toString(),
            rs.getString("review_status"),
            rs.getString("review_comment"),
            rs.getString("reviewed_by"),
            reviewedAt == null ? null : reviewedAt.toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }

    @Override
    public AiDiscLevelReview upsertAiDiscLevelReview(String discLevelResultId, String reviewStatus, String reviewComment, String reviewedBy, Instant reviewedAt) {
        String id = UUID.randomUUID().toString();
        Instant updatedAt = Instant.now();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_ai_disc_level_reviews(id, disc_level_result_id, review_status, review_comment, reviewed_by, reviewed_at, updated_at)
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?)
            ON CONFLICT (disc_level_result_id) DO UPDATE SET
                review_status = EXCLUDED.review_status,
                review_comment = EXCLUDED.review_comment,
                reviewed_by = EXCLUDED.reviewed_by,
                reviewed_at = EXCLUDED.reviewed_at,
                updated_at = EXCLUDED.updated_at
            RETURNING id, updated_at
            """)) {
            statement.setString(1, id);
            statement.setString(2, discLevelResultId);
            statement.setString(3, reviewStatus);
            statement.setString(4, reviewComment);
            statement.setString(5, reviewedBy);
            statement.setTimestamp(6, reviewedAt == null ? null : Timestamp.from(reviewedAt));
            statement.setTimestamp(7, Timestamp.from(updatedAt));
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return new AiDiscLevelReview(
                    rs.getObject("id", UUID.class).toString(),
                    discLevelResultId,
                    reviewStatus,
                    reviewComment,
                    reviewedBy,
                    reviewedAt,
                    rs.getTimestamp("updated_at").toInstant()
                );
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save AI disc level review", ex);
        }
    }

    @Override
    public Optional<AiDiscLevelReview> findAiDiscLevelReview(String discLevelResultId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, disc_level_result_id, review_status, review_comment, reviewed_by, reviewed_at, updated_at
            FROM domain_ai_disc_level_reviews WHERE disc_level_result_id = ?::uuid
            """)) {
            statement.setString(1, discLevelResultId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readAiDiscLevelReview(rs));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find AI disc level review", ex);
        }
    }

    @Override
    public List<AiDiscLevelReview> findAiDiscLevelReviewsByAnalysisRunId(String analysisRunId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT r.id, r.disc_level_result_id, r.review_status, r.review_comment, r.reviewed_by, r.reviewed_at, r.updated_at
            FROM domain_ai_disc_level_reviews r
            JOIN domain_ai_disc_level_results d ON d.id = r.disc_level_result_id
            WHERE d.analysis_run_id = ?::uuid
            """)) {
            statement.setString(1, analysisRunId);
            try (ResultSet rs = statement.executeQuery()) {
                List<AiDiscLevelReview> results = new ArrayList<>();
                while (rs.next()) results.add(readAiDiscLevelReview(rs));
                return results;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not list AI disc level reviews", ex);
        }
    }

    private AiDiscLevelReview readAiDiscLevelReview(ResultSet rs) throws Exception {
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        return new AiDiscLevelReview(
            rs.getObject("id", UUID.class).toString(),
            rs.getObject("disc_level_result_id", UUID.class).toString(),
            rs.getString("review_status"),
            rs.getString("review_comment"),
            rs.getString("reviewed_by"),
            reviewedAt == null ? null : reviewedAt.toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }

    @Override
    public AiMeasurementCorrection saveAiMeasurementCorrection(AiMeasurementCorrection correction) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_ai_measurement_corrections(
                id, measurement_id, analysis_run_id, field, original_value, corrected_value, unit, comment, reviewed_by, created_at
            ) VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, correction.id());
            statement.setString(2, correction.measurementId());
            statement.setString(3, correction.analysisRunId());
            statement.setString(4, correction.field());
            setNullableDouble(statement, 5, correction.originalValue());
            statement.setDouble(6, correction.correctedValue());
            statement.setString(7, correction.unit());
            statement.setString(8, correction.comment());
            statement.setString(9, correction.reviewedBy());
            statement.setTimestamp(10, Timestamp.from(correction.createdAt()));
            statement.executeUpdate();
            return correction;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save AI measurement correction", ex);
        }
    }

    // ---- Stage 80: contextual chat ----

    @Override
    public ChatConversation saveChatConversation(ChatConversation conversation) {
        try (Connection connection = connection()) {
            insertChatConversation(connection, conversation);
            return conversation;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save chat conversation", ex);
        }
    }

    @Override
    public ChatMessage saveChatMessage(ChatMessage message) {
        try (Connection connection = connection()) {
            insertChatMessage(connection, message);
            return message;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save chat message", ex);
        }
    }

    @Override
    public void saveChatTurn(ChatConversation conversationToCreate, ChatMessage userMessage, ChatMessage assistantMessage) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                if (conversationToCreate != null) insertChatConversation(connection, conversationToCreate);
                insertChatMessage(connection, userMessage);
                insertChatMessage(connection, assistantMessage);
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save chat turn", ex);
        }
    }

    private void insertChatConversation(Connection connection, ChatConversation conversation) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_chat_conversations(id, study_id, created_at) VALUES (?::uuid, ?::uuid, ?)
            """)) {
            statement.setString(1, conversation.id());
            statement.setString(2, conversation.studyId());
            statement.setTimestamp(3, Timestamp.from(conversation.createdAt()));
            statement.executeUpdate();
        }
    }

    private void insertChatMessage(Connection connection, ChatMessage message) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_chat_messages(
                id, conversation_id, role, content, created_at, model, provider, prompt_version, analysis_run_id, baseline_study_id
            ) VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?::uuid, ?::uuid)
            """)) {
            statement.setString(1, message.id());
            statement.setString(2, message.conversationId());
            statement.setString(3, message.role());
            statement.setString(4, message.content());
            statement.setTimestamp(5, Timestamp.from(message.createdAt()));
            statement.setString(6, message.model());
            statement.setString(7, message.provider());
            statement.setString(8, message.promptVersion());
            statement.setString(9, message.analysisRunId());
            statement.setString(10, message.baselineStudyId());
            statement.executeUpdate();
        }
    }

    @Override
    public List<ChatConversation> findChatConversationsByStudyId(String studyId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, study_id, created_at FROM domain_chat_conversations WHERE study_id = ?::uuid ORDER BY created_at DESC
            """)) {
            statement.setString(1, studyId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ChatConversation> conversations = new ArrayList<>();
                while (rs.next()) conversations.add(readChatConversation(rs));
                return conversations;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not list chat conversations", ex);
        }
    }

    @Override
    public Optional<ChatConversation> findChatConversationById(String conversationId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, study_id, created_at FROM domain_chat_conversations WHERE id = ?::uuid
            """)) {
            statement.setString(1, conversationId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readChatConversation(rs));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find chat conversation", ex);
        }
    }

    private ChatConversation readChatConversation(ResultSet rs) throws Exception {
        return new ChatConversation(
            rs.getObject("id", UUID.class).toString(),
            rs.getObject("study_id", UUID.class).toString(),
            rs.getTimestamp("created_at").toInstant()
        );
    }

    @Override
    public List<ChatMessage> findChatMessagesByConversationId(String conversationId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, conversation_id, role, content, created_at, model, provider, prompt_version, analysis_run_id, baseline_study_id
            FROM domain_chat_messages WHERE conversation_id = ?::uuid ORDER BY created_at ASC
            """)) {
            statement.setString(1, conversationId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ChatMessage> messages = new ArrayList<>();
                while (rs.next()) {
                    messages.add(new ChatMessage(
                        rs.getObject("id", UUID.class).toString(),
                        rs.getObject("conversation_id", UUID.class).toString(),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("model"),
                        rs.getString("provider"),
                        rs.getString("prompt_version"),
                        rs.getString("analysis_run_id"),
                        rs.getString("baseline_study_id")
                    ));
                }
                return messages;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not list chat messages", ex);
        }
    }

    @Override
    public List<AiMeasurementCorrection> findAiMeasurementCorrectionsByAnalysisRunId(String analysisRunId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, measurement_id, analysis_run_id, field, original_value, corrected_value, unit, comment, reviewed_by, created_at
            FROM domain_ai_measurement_corrections WHERE analysis_run_id = ?::uuid ORDER BY created_at ASC
            """)) {
            statement.setString(1, analysisRunId);
            try (ResultSet rs = statement.executeQuery()) {
                List<AiMeasurementCorrection> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new AiMeasurementCorrection(
                        rs.getObject("id", UUID.class).toString(),
                        rs.getObject("measurement_id", UUID.class).toString(),
                        rs.getObject("analysis_run_id", UUID.class).toString(),
                        rs.getString("field"),
                        getNullableDouble(rs, "original_value"),
                        rs.getDouble("corrected_value"),
                        rs.getString("unit"),
                        rs.getString("comment"),
                        rs.getString("reviewed_by"),
                        rs.getTimestamp("created_at").toInstant()
                    ));
                }
                return results;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not list AI measurement corrections", ex);
        }
    }

    // ---- EXT-04B2: visual evidence regions ----

    @Override
    public AiEvidenceRegion saveAiEvidenceRegion(AiEvidenceRegion region) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_ai_evidence_regions(
                id, analysis_run_id, disc_level_result_id, measurement_id, plane, level, structure, slice_index,
                origin, geometry_type, geometry, coordinate_space_ref, model_id, model_sha256, pipeline_version,
                confidence, experimental, label, comment, authored_by, created_at
            ) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, region.id());
            statement.setString(2, region.analysisRunId());
            statement.setString(3, region.discLevelResultId());
            statement.setString(4, region.measurementId());
            statement.setString(5, region.plane());
            statement.setString(6, region.level());
            statement.setString(7, region.structure());
            if (region.sliceIndex() == null) statement.setNull(8, java.sql.Types.INTEGER);
            else statement.setInt(8, region.sliceIndex());
            statement.setString(9, region.origin());
            statement.setString(10, region.geometryType());
            statement.setString(11, objectMapper.writeValueAsString(region.geometry()));
            statement.setString(12, region.coordinateSpaceRef() == null ? null : objectMapper.writeValueAsString(region.coordinateSpaceRef()));
            statement.setString(13, region.modelId());
            statement.setString(14, region.modelSha256());
            statement.setString(15, region.pipelineVersion());
            setNullableDouble(statement, 16, region.confidence());
            statement.setBoolean(17, region.experimental());
            statement.setString(18, region.label());
            statement.setString(19, region.comment());
            statement.setString(20, region.authoredBy());
            statement.setTimestamp(21, Timestamp.from(region.createdAt()));
            statement.executeUpdate();
            return region;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save AI evidence region", ex);
        }
    }

    @Override
    public List<AiEvidenceRegion> findAiEvidenceRegionsByAnalysisRunId(String analysisRunId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, analysis_run_id, disc_level_result_id, measurement_id, plane, level, structure, slice_index,
                   origin, geometry_type, geometry, coordinate_space_ref, model_id, model_sha256, pipeline_version,
                   confidence, experimental, label, comment, authored_by, created_at
            FROM domain_ai_evidence_regions WHERE analysis_run_id = ?::uuid ORDER BY created_at ASC
            """)) {
            statement.setString(1, analysisRunId);
            try (ResultSet rs = statement.executeQuery()) {
                List<AiEvidenceRegion> results = new ArrayList<>();
                while (rs.next()) results.add(readAiEvidenceRegion(rs));
                return results;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not list AI evidence regions", ex);
        }
    }

    private AiEvidenceRegion readAiEvidenceRegion(ResultSet rs) throws Exception {
        int sliceIndex = rs.getInt("slice_index");
        boolean sliceIndexNull = rs.wasNull();
        String geometryJson = rs.getString("geometry");
        String coordinateSpaceJson = rs.getString("coordinate_space_ref");
        return new AiEvidenceRegion(
            rs.getObject("id", UUID.class).toString(),
            rs.getObject("analysis_run_id", UUID.class).toString(),
            rs.getString("disc_level_result_id"),
            rs.getString("measurement_id"),
            rs.getString("plane"),
            rs.getString("level"),
            rs.getString("structure"),
            sliceIndexNull ? null : sliceIndex,
            rs.getString("origin"),
            rs.getString("geometry_type"),
            objectMapper.readValue(geometryJson, JSON_MAP),
            coordinateSpaceJson == null ? null : objectMapper.readValue(coordinateSpaceJson, JSON_MAP),
            rs.getString("model_id"),
            rs.getString("model_sha256"),
            rs.getString("pipeline_version"),
            getNullableDouble(rs, "confidence"),
            rs.getBoolean("experimental"),
            rs.getString("label"),
            rs.getString("comment"),
            rs.getString("authored_by"),
            rs.getTimestamp("created_at").toInstant()
        );
    }

    @Override
    public AiEvidenceRegionCorrection saveAiEvidenceRegionCorrection(AiEvidenceRegionCorrection correction) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_ai_evidence_region_corrections(
                id, evidence_region_id, analysis_run_id, original_geometry_type, original_geometry,
                corrected_geometry_type, corrected_geometry, comment, reviewed_by, created_at
            ) VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?)
            """)) {
            statement.setString(1, correction.id());
            statement.setString(2, correction.evidenceRegionId());
            statement.setString(3, correction.analysisRunId());
            statement.setString(4, correction.originalGeometryType());
            statement.setString(5, correction.originalGeometry() == null ? null : objectMapper.writeValueAsString(correction.originalGeometry()));
            statement.setString(6, correction.correctedGeometryType());
            statement.setString(7, objectMapper.writeValueAsString(correction.correctedGeometry()));
            statement.setString(8, correction.comment());
            statement.setString(9, correction.reviewedBy());
            statement.setTimestamp(10, Timestamp.from(correction.createdAt()));
            statement.executeUpdate();
            return correction;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save AI evidence region correction", ex);
        }
    }

    @Override
    public List<AiEvidenceRegionCorrection> findAiEvidenceRegionCorrectionsByAnalysisRunId(String analysisRunId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, evidence_region_id, analysis_run_id, original_geometry_type, original_geometry,
                   corrected_geometry_type, corrected_geometry, comment, reviewed_by, created_at
            FROM domain_ai_evidence_region_corrections WHERE analysis_run_id = ?::uuid ORDER BY created_at ASC
            """)) {
            statement.setString(1, analysisRunId);
            try (ResultSet rs = statement.executeQuery()) {
                List<AiEvidenceRegionCorrection> results = new ArrayList<>();
                while (rs.next()) {
                    String originalType = rs.getString("original_geometry_type");
                    String originalJson = rs.getString("original_geometry");
                    results.add(new AiEvidenceRegionCorrection(
                        rs.getObject("id", UUID.class).toString(),
                        rs.getObject("evidence_region_id", UUID.class).toString(),
                        rs.getObject("analysis_run_id", UUID.class).toString(),
                        originalType,
                        originalJson == null ? null : objectMapper.readValue(originalJson, JSON_MAP),
                        rs.getString("corrected_geometry_type"),
                        objectMapper.readValue(rs.getString("corrected_geometry"), JSON_MAP),
                        rs.getString("comment"),
                        rs.getString("reviewed_by"),
                        rs.getTimestamp("created_at").toInstant()
                    ));
                }
                return results;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not list AI evidence region corrections", ex);
        }
    }
}
