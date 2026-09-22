package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.DurableObjectAsset;
import ar.edu.uade.pfi.backend.repository.SqlMigrationRunner;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "pfi.persistence.mode", havingValue = "postgres")
public class PostgresDurableObjectAssetRepository implements DurableObjectAssetRepository {

    private final String jdbcUrl;

    @Autowired
    public PostgresDurableObjectAssetRepository(
        @Value("${pfi.database.url:${DATABASE_URL:}}") String databaseUrl) {
        this(databaseUrl, true);
    }

    public PostgresDurableObjectAssetRepository(String databaseUrl, boolean applyMigrations) {
        this.jdbcUrl = toJdbcUrl(databaseUrl == null ? "" : databaseUrl.trim());
        if (applyMigrations) {
            try (Connection connection = connection()) {
                new SqlMigrationRunner(SqlMigrationRunner.DEFAULT_MIGRATIONS_DIRECTORY).apply(connection);
            } catch (Exception ex) {
                throw new IllegalStateException("Could not initialize durable object asset repository", ex);
            }
        }
    }

    @Override
    public DurableObjectAsset save(DurableObjectAsset asset) {
        try (Connection connection = connection();
            PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO domain_durable_object_assets(
                    asset_id, study_id, run_id, report_id, object_key, content_type,
                    size_bytes, sha256, artifact_type, storage_provider, original_filename, created_at
                ) VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, asset.assetId());
            statement.setString(2, asset.studyId());
            statement.setString(3, asset.runId());
            statement.setString(4, asset.reportId());
            statement.setString(5, asset.objectKey());
            statement.setString(6, asset.contentType());
            statement.setLong(7, asset.sizeBytes());
            statement.setString(8, asset.sha256());
            statement.setString(9, asset.artifactType());
            statement.setString(10, asset.storageProvider());
            statement.setString(11, asset.originalFilename());
            statement.setTimestamp(12, Timestamp.from(asset.createdAt()));
            statement.executeUpdate();
            return asset;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not persist durable object asset metadata", ex);
        }
    }

    @Override
    public Optional<DurableObjectAsset> findById(String assetId) {
        try (Connection connection = connection();
            PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + " WHERE asset_id = ?::uuid")) {
            statement.setString(1, assetId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(readRow(rs)) : Optional.empty();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find durable object asset metadata", ex);
        }
    }

    @Override
    public Optional<DurableObjectAsset> findLatestStudyInput(String studyId) {
        try (Connection connection = connection();
            PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS
                    + " WHERE study_id = ? AND artifact_type = 'study_input'"
                    + " ORDER BY created_at DESC LIMIT 1")) {
            statement.setString(1, studyId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(readRow(rs)) : Optional.empty();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find latest durable study input asset", ex);
        }
    }

    @Override
    public List<DurableObjectAsset> findByStudyId(String studyId) {
        try (Connection connection = connection();
            PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + " WHERE study_id = ? ORDER BY created_at ASC")) {
            statement.setString(1, studyId);
            try (ResultSet rs = statement.executeQuery()) {
                List<DurableObjectAsset> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(readRow(rs));
                }
                return results;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not list durable object assets for study", ex);
        }
    }

    private static final String SELECT_COLUMNS =
        """
        SELECT asset_id, study_id, run_id, report_id, object_key, content_type,
               size_bytes, sha256, artifact_type, storage_provider, original_filename, created_at
        FROM domain_durable_object_assets
        """;

    private DurableObjectAsset readRow(ResultSet rs) throws Exception {
        return new DurableObjectAsset(
            rs.getObject("asset_id", UUID.class).toString(),
            rs.getString("study_id"),
            rs.getString("run_id"),
            rs.getString("report_id"),
            rs.getString("object_key"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getString("sha256"),
            rs.getString("artifact_type"),
            rs.getString("storage_provider"),
            rs.getString("original_filename"),
            rs.getTimestamp("created_at").toInstant());
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(jdbcUrl);
    }

    private String toJdbcUrl(String databaseUrl) {
        if (databaseUrl.isBlank()) return "";
        if (databaseUrl.startsWith("jdbc:postgresql://")) return databaseUrl;
        if (!databaseUrl.startsWith("postgres://") && !databaseUrl.startsWith("postgresql://")) {
            return databaseUrl;
        }
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
            String query = uri.getRawQuery();
            StringBuilder jdbc = new StringBuilder("jdbc:postgresql://")
                .append(uri.getHost())
                .append(uri.getPort() < 0 ? "" : ":" + uri.getPort())
                .append(uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath());
            String separator = "?";
            if (query != null && !query.isBlank()) {
                jdbc.append("?").append(query);
                separator = "&";
            }
            if (!user.isBlank()) {
                jdbc.append(separator)
                    .append("user=").append(URLEncoder.encode(user, StandardCharsets.UTF_8))
                    .append("&password=").append(URLEncoder.encode(password, StandardCharsets.UTF_8));
            }
            return jdbc.toString();
        } catch (RuntimeException ex) {
            return databaseUrl;
        }
    }
}
