package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ar.edu.uade.pfi.backend.config.ObjectStorageProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeDiagnosticsServiceTest {
  @Test
  void diagnosticsExposeOnlyNonSecretOperationalState() {
    RuntimeDiagnosticsService service =
        new RuntimeDiagnosticsService(
            (javax.sql.DataSource) null, "postgres", new ObjectStorageProperties("gcs", "/secret/local/path", "secret-bucket"));

    Map<String, Object> diagnostics = service.diagnostics();

    assertEquals("ok", diagnostics.get("status"));
    assertEquals(Map.of("mode", "postgres", "postgresEnabled", true), diagnostics.get("persistence"));
    assertEquals(Map.of("provider", "gcs", "gcsConfigured", true), diagnostics.get("storage"));
    @SuppressWarnings("unchecked")
    Map<String, Object> dataSource = (Map<String, Object>) diagnostics.get("dataSource");
    assertEquals(false, dataSource.get("beanPresent"));
    assertEquals("none", dataSource.get("poolType"));

    String rendered = diagnostics.toString().toLowerCase();
    assertFalse(rendered.contains("secret-bucket"));
    assertFalse(rendered.contains("/secret/local/path"));
    assertFalse(rendered.contains("database_url"));
    assertFalse(rendered.contains("password"));
  }
}