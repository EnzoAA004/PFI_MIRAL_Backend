package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.config.ObjectStorageProperties;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Safe runtime-only diagnostics for Cloud Run routing investigations. Never includes JDBC URLs,
 * bucket names, secret values, auth tokens, request headers, or filesystem paths.
 */
@Service
public class RuntimeDiagnosticsService {
  private final DataSource dataSource;
  private final String persistenceMode;
  private final ObjectStorageProperties storageProperties;

  @Autowired
  public RuntimeDiagnosticsService(
      ObjectProvider<DataSource> dataSourceProvider,
      @Value("${pfi.persistence.mode:memory}") String persistenceMode,
      ObjectStorageProperties storageProperties) {
    this(dataSourceProvider.getIfAvailable(), persistenceMode, storageProperties);
  }

  RuntimeDiagnosticsService(
      @Nullable DataSource dataSource,
      String persistenceMode,
      ObjectStorageProperties storageProperties) {
    this.dataSource = dataSource;
    this.persistenceMode =
        persistenceMode == null || persistenceMode.isBlank() ? "memory" : persistenceMode.trim();
    this.storageProperties = storageProperties;
  }

  public Map<String, Object> diagnostics() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", "ok");
    result.put("cloudRun", cloudRun());
    result.put("persistence", persistence());
    result.put("storage", storage());
    result.put("dataSource", dataSource());
    return result;
  }

  public String revision() {
    return env("K_REVISION");
  }

  private Map<String, Object> cloudRun() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("service", blankToUnavailable(env("K_SERVICE")));
    result.put("revision", blankToUnavailable(revision()));
    result.put("configuration", blankToUnavailable(env("K_CONFIGURATION")));
    result.put("detected", !env("K_SERVICE").isBlank() || !revision().isBlank());
    return result;
  }

  private Map<String, Object> persistence() {
    return Map.of(
        "mode", persistenceMode,
        "postgresEnabled", "postgres".equalsIgnoreCase(persistenceMode));
  }

  private Map<String, Object> storage() {
    String provider = storageProperties == null ? "unavailable" : storageProperties.resolvedProvider();
    return Map.of("provider", provider, "gcsConfigured", "gcs".equals(provider));
  }

  private Map<String, Object> dataSource() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("beanPresent", dataSource != null);
    if (dataSource == null) {
      result.put("poolType", "none");
      return result;
    }
    result.put("poolType", dataSource.getClass().getSimpleName());
    if (dataSource instanceof HikariDataSource hikari) {
      result.put("maximumPoolSize", hikari.getMaximumPoolSize());
      result.put("minimumIdle", hikari.getMinimumIdle());
      HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
      result.put("poolMetricsAvailable", pool != null);
      if (pool != null) {
        result.put("totalConnections", pool.getTotalConnections());
        result.put("activeConnections", pool.getActiveConnections());
        result.put("idleConnections", pool.getIdleConnections());
        result.put("threadsAwaitingConnection", pool.getThreadsAwaitingConnection());
      }
    } else {
      result.put("poolMetricsAvailable", false);
    }
    return result;
  }

  private static String env(String key) {
    String value = System.getenv(key);
    return value == null ? "" : value.trim();
  }

  private static String blankToUnavailable(String value) {
    return value == null || value.isBlank() ? "unavailable" : value;
  }
}