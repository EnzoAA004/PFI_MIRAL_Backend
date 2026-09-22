package ar.edu.uade.pfi.backend.repository;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Normalizes a Postgres connection string into a JDBC URL the driver accepts directly.
 *
 * <p>Extracted from the eight near-identical private copies of this method that used to
 * live one per {@code PostgresXxx} class. A {@code jdbc:postgresql://...} value (plain
 * TCP, or the Cloud SQL Java Connector's {@code jdbc:postgresql:///{db}?cloudSqlInstance=...
 * &socketFactory=...} shape -- both start with this exact prefix) passes through
 * unchanged; a bare {@code postgres://user:pass@host/db} value (Heroku/Render-style, used
 * by {@code DATABASE_URL} in some environments) is rewritten into the same JDBC shape.
 */
public final class JdbcUrlNormalizer {

    private JdbcUrlNormalizer() {
    }

    public static String toJdbcUrl(String databaseUrl) {
        if (databaseUrl == null || databaseUrl.isBlank()) return "";
        String trimmed = databaseUrl.trim();
        if (trimmed.startsWith("jdbc:postgresql://")) return trimmed;
        if (!trimmed.startsWith("postgres://") && !trimmed.startsWith("postgresql://")) {
            return trimmed;
        }
        try {
            URI uri = URI.create(trimmed);
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
            return trimmed;
        }
    }
}
