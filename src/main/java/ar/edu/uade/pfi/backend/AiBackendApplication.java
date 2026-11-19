package ar.edu.uade.pfi.backend;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Excludes Spring Boot's own DataSource autoconfiguration: this app manages its single
 * pooled DataSource itself (PfiDataSourceConfig, conditional on pfi.persistence.mode),
 * doesn't set spring.datasource.*, and doesn't use JdbcTemplate/a transaction manager.
 * Without the exclude, autoconfiguration can fail startup in memory mode (no URL to
 * configure an embedded/external DataSource from).
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
