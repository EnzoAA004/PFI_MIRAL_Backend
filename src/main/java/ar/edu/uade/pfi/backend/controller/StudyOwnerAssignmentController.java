package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import ar.edu.uade.pfi.backend.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Camino de asignación explícito y auditable para estudios legacy sin grant de owner. */
@RestController
@RequestMapping("/api/admin/studies")
public class StudyOwnerAssignmentController {
  private final RoleAuthorizationService authorization;
  private final StudyRepository repository;
  private final AuditService auditService;

  public StudyOwnerAssignmentController(RoleAuthorizationService authorization, StudyRepository repository, AuditService auditService) {
    this.authorization = authorization;
    this.repository = repository;
    this.auditService = auditService;
  }

  @PostMapping("/{caseId}/owner")
  public Map<String, String> assignOwner(
      @PathVariable String caseId,
      @RequestBody(required = false) Map<String, String> body,
      HttpServletRequest request) {
    authorization.requireAdmin(request, caseId);
    if (repository.findStudyByCaseId(caseId).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Estudio inexistente.");
    }
    String principalId = body == null ? null : body.get("principalId");
    if (principalId == null || principalId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "principalId es obligatorio.");
    }
    if (!repository.claimStudyOwner(caseId, principalId.trim())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "El estudio ya tiene propietario asignado.");
    }
    Object claimsValue = request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE);
    String actor = claimsValue instanceof TokenService.Claims claims ? claims.subject() : "admin";
    auditService.record(actor, "study.owner.assigned", caseId, "unavailable", Map.of("ownerPrincipalId", principalId.trim()));
    return Map.of("status", "assigned", "caseId", caseId, "principalId", principalId.trim());
  }
}
