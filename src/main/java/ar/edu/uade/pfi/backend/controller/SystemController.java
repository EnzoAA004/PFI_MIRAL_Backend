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
   * Minimal liveness endpoint: intentionally returns nothing beyond a status flag. No database,
   * AI Module, secrets, or infra details — see /api/system/diagnostics (ADMIN-only) for anything
   * richer. In Cloud Run it also emits X-PFI-Revision for routing traceability.
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
   * Public but non-secret runtime state for Cloud Run routing diagnostics. This deliberately omits
   * URLs, bucket names, DB credentials, request headers, actor data, and any secret material.
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

  /** Triggers an AI Module warmup call — ADMIN-only, it is an operational action, not a read. */
  @PostMapping("/warmup")
  public Map<String, Object> warmup(HttpServletRequest request) {
    authorizationService.requireAdmin(request, "system.warmup");
    return systemDiagnosticsService.warmup();
  }
}
