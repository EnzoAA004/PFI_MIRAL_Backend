package ar.edu.uade.pfi.backend.config;

import ar.edu.uade.pfi.backend.repository.JdbcUrlNormalizer;
import ar.edu.uade.pfi.backend.repository.PfiHikariDataSourceFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El único {@link DataSource} con pool de toda la aplicación, compartido por cada
 * repositorio/servicio {@code PostgresXxx} de este backend. Un pool por instancia de Cloud Run
 * (no uno por clase de repositorio) es lo que mantiene predecible el peor caso de cantidad de
 * conexiones contra Cloud SQL: {@code maximumPoolSize} (4) x Cloud Run
 * {@code max-instances} (2) = 8 conexiones posibles, bien por debajo del tope de
 * conexiones de db-f1-micro -- ver docs/cloud/CLOUD_REAL_DEPLOYMENT_EXECUTION_PLAN.md.
 *
 * <p>Solo se crea con {@code pfi.persistence.mode=postgres}; en modo memoria no existe
 * ningún bean DataSource; ver los servicios {@code PostgresXxx} siempre activos
 * (auth/review/catalog) para ver cómo lo toleran vía {@code ObjectProvider}.
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
