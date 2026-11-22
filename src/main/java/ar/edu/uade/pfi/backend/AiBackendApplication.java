package ar.edu.uade.pfi.backend;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Excluye la autoconfiguración de DataSource propia de Spring Boot: esta app administra su único
 * DataSource con pool por su cuenta (PfiDataSourceConfig, condicional a pfi.persistence.mode),
 * no define spring.datasource.* y no usa JdbcTemplate ni un transaction manager.
 * Sin la exclusión, la autoconfiguración puede hacer fallar el arranque en modo memoria (no hay URL
 * desde la cual configurar un DataSource embebido/externo).
 */
@SpringBootApplication(exclude = {
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class
})
@ConfigurationPropertiesScan
public class AiBackendApplication {
  public static void main(String[] args) {
    org.springframework.boot.SpringApplication.run(AiBackendApplication.class, args);
  }
}
