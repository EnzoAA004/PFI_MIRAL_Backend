package ar.edu.uade.pfi.backend.config;

import ar.edu.uade.pfi.backend.repository.JdbcUrlNormalizer;
import ar.edu.uade.pfi.backend.repository.PfiHikariDataSourceFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single application-wide pooled {@link DataSource}, shared by every
 * {@code PostgresXxx} repository/service in this backend. One pool per Cloud Run
 * instance (not one per repository class) is what keeps the worst-case connection
 * count against Cloud SQL predictable: {@code maximumPoolSize} (4) x Cloud Run
 * {@code max-instances} (2) = 8 possible connections, well under db-f1-micro's
 * connection ceiling -- see docs/cloud/CLOUD_REAL_DEPLOYMENT_EXECUTION_PLAN.md.
 *
 * <p>Only created in {@code pfi.persistence.mode=postgres}; in memory mode no
 * DataSource bean exists at all; see the always-on {@code PostgresXxx} services
 * (auth/review/catalog) for how they tolerate that via {@code ObjectProvider}.
 */
@Configuration
@ConditionalOnProperty(name = "pfi.persistence.mode", havingValue = "postgres")
public class PfiDataSourceConfig {

    @Bean
    public DataSource pfiDataSource(@Value("${pfi.database.url:${DATABASE_URL:}}") String databaseUrl) {
        String jdbcUrl = JdbcUrlNormalizer.toJdbcUrl(databaseUrl == null ? "" : databaseUrl.trim());
        return PfiHikariDataSourceFactory.create(jdbcUrl);
    }
}
