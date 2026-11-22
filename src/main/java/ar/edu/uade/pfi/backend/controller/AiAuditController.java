package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.dto.AuditEventResponseDto;
import ar.edu.uade.pfi.backend.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * La búsqueda del audit trail a nivel de todo el sistema (consultas arbitrarias por traceId/entityId entre todos los usuarios y casos) es
 * una capacidad administrativa, no clínica/académica, por lo que requiere ADMIN.
 */
@RestController
@RequestMapping("/api/ai/audit-events")
public class AiAuditController {
  private final AuditService auditService;
  private final RoleAuthorizationService authorizationService;

  @Autowired
  public AiAuditController(
      AuditService auditService, RoleAuthorizationService authorizationService) {
    this.auditService = auditService;
    this.authorizationService = authorizationService;
  }

  @GetMapping
  public List<AuditEventResponseDto> findEvents(
      @RequestParam(required = false) String traceId,
      @RequestParam(required = false) String entityId,
      HttpServletRequest request) {
    authorizationService.requireAdmin(request, "audit.events");
    if (traceId != null && !traceId.isBlank()) return auditService.findByTraceId(traceId);
    if (entityId != null && !entityId.isBlank()) return auditService.findByEntityId(entityId);
    return List.of();
  }
}
