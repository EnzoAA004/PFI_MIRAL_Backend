package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.service.RuntimeDiagnosticsService;
import ar.edu.uade.pfi.backend.service.SystemDiagnosticsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {
  private final SystemDiagnosticsService systemDiagnosticsService;
  private final RuntimeDiagnosticsService runtimeDiagnosticsService;
  private final RoleAuthorizationService authorizationService;

  public SystemController(
      SystemDiagnosticsService systemDiagnosticsService,
      RoleAuthorizationService authorizationService) {
    this(systemDiagnosticsService, authorizationService, null);
  }

  @Autowired
  public SystemController(
      SystemDiagnosticsService systemDiagnosticsService,
      RoleAuthorizationService authorizationService,
      RuntimeDiagnosticsService runtimeDiagnosticsService) {
    this.systemDiagnosticsService = systemDiagnosticsService;
    this.runtimeDiagnosticsService = runtimeDiagnosticsService;
    this.authorizationService = authorizationService;
  }

  /**
   * Endpoint de liveness mínimo: intencionalmente no devuelve nada más que un flag de estado. Sin base de datos,
   * módulo de IA, secretos ni detalles de infra — ver /api/system/diagnostics (solo ADMIN) para algo
   * más rico. En Cloud Run además emite X-PFI-Revision para la trazabilidad del routing.
   */
  @GetMapping("/health")
  public Map<String, Object> health(HttpServletResponse response) {
    if (runtimeDiagnosticsService != null) {
      String revision = runtimeDiagnosticsService.revision();
      if (revision != null && !revision.isBlank()) {
        response.setHeader("X-PFI-Revision", revision);
      }
    }
    return Map.of("status", "ok");
  }

  /**
   * Estado de runtime público pero no secreto para diagnóstico del routing de Cloud Run. Omite deliberadamente
   * URLs, nombres de buckets, credenciales de DB, headers de request, datos del actor y cualquier material secreto.
   */
  @GetMapping("/runtime-diagnostics")
  public Map<String, Object> runtimeDiagnostics() {
    if (runtimeDiagnosticsService == null) {
      return Map.of("status", "unavailable");
    }
    return runtimeDiagnosticsService.diagnostics();
  }

  @GetMapping("/diagnostics")
  public Map<String, Object> diagnostics(HttpServletRequest request) {
    authorizationService.requireAdmin(request, "system.diagnostics");
    return systemDiagnosticsService.diagnostics();
  }

  /** Dispara una llamada de warmup al módulo de IA — solo ADMIN, es una acción operativa, no una lectura. */
  @PostMapping("/warmup")
  public Map<String, Object> warmup(HttpServletRequest request) {
    authorizationService.requireAdmin(request, "system.warmup");
    return systemDiagnosticsService.warmup();
  }
}
