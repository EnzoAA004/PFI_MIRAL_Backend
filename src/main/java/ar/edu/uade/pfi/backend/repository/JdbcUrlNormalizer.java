package ar.edu.uade.pfi.backend.repository;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Normaliza un connection string de Postgres a una URL JDBC que el driver acepta directamente.
 *
 * <p>Extraído de las ocho copias privadas casi idénticas de este método que antes
 * vivían una por cada clase {@code PostgresXxx}. Un valor {@code jdbc:postgresql://...} (TCP
 * plano, o la forma {@code jdbc:postgresql:///{db}?cloudSqlInstance=...
 * &socketFactory=...} del Cloud SQL Java Connector -- ambas empiezan con este mismo prefijo) pasa
 * sin cambios; un valor {@code postgres://user:pass@host/db} pelado (estilo Heroku/Render, usado
 * por {@code DATABASE_URL} en algunos entornos) se reescribe a la misma forma JDBC.
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
