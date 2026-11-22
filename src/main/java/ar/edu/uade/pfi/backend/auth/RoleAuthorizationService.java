package ar.edu.uade.pfi.backend.auth;

import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import ar.edu.uade.pfi.backend.web.filter.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoleAuthorizationService {
  private final AuditService auditService;
  private final StudyRepository studyRepository;

  public RoleAuthorizationService(AuditService auditService) {
    this.auditService = auditService;
    this.studyRepository = null;
  }

  @Autowired
  public RoleAuthorizationService(AuditService auditService, StudyRepository studyRepository) {
    this.auditService = auditService;
    this.studyRepository = studyRepository;
  }

  public void requireStudyRead(HttpServletRequest request, String caseId) { requireStudy(request, caseId, false); }

  public void requireStudyWrite(HttpServletRequest request, String caseId) { requireStudy(request, caseId, true); }

  public void requireStudyResourceWrite(HttpServletRequest request, String resourceId) {
    if (studyRepository == null) return;
    String caseId = studyRepository.resolveStudyCaseId(resourceId).orElse(null);
    if (caseId == null) denyStudy(request, resourceId);
    requireStudyWrite(request, caseId);
  }

  /** Exige que un recurso secundario pertenezca al estudio ya autorizado en la ruta/body. */
  public void requireResourceBelongsToStudy(HttpServletRequest request, String resourceId, String expectedCaseId, boolean write) {
    if (resourceId == null || resourceId.isBlank()) return;
    if (studyRepository == null) return;
    String resolvedCaseId = studyRepository.resolveStudyCaseId(resourceId).orElse(null);
    if (resolvedCaseId == null || !resolvedCaseId.equals(expectedCaseId)) denyStudy(request, expectedCaseId);
    requireStudy(request, expectedCaseId, write);
  }

  public void claimOrRequireStudyWrite(HttpServletRequest request, String caseId) {
    if (studyRepository == null) return;
    String principal = principal(request);
    if (studyRepository.hasStudyAccess(caseId, principal, true)) return;
    // Las filas legacy deben asignarse mediante un workflow administrativo/de owner explícito; un endpoint
    // de upload solo puede reclamar un caso antes de que exista la primera fila durable de Study.
    if (studyRepository.findStudyByCaseId(caseId).isPresent()
        || !studyRepository.claimStudyOwner(caseId, principal)) denyStudy(request, caseId);
  }

  public List<String> accessibleStudyCaseIds(HttpServletRequest request) {
    return studyRepository == null ? List.of() : studyRepository.findAccessibleCaseIds(principal(request));
  }

  public void requireSubjectAccess(HttpServletRequest request, String subjectRef) {
    if (studyRepository == null) return;
    String actor = principal(request);
    boolean allowed = studyRepository.findStudiesBySubjectRef(subjectRef).stream()
        .anyMatch(study -> studyRepository.hasStudyAccess(study.caseId(), actor, false));
    if (!allowed) denyStudy(request, subjectRef);
  }

  public List<String> accessibleSubjectRefs(HttpServletRequest request) {
    if (studyRepository == null) return List.of();
    String actor = principal(request);
    return studyRepository.findAllStudies().stream()
        .filter(study -> study.subjectRef() != null && studyRepository.hasStudyAccess(study.caseId(), actor, false))
        .map(study -> study.subjectRef().toLowerCase(java.util.Locale.ROOT)).distinct().toList();
  }

  public void grantStudyAccess(HttpServletRequest request, String caseId, String principalId, String permission) {
    if (studyRepository == null) return;
    String principal = principal(request);
    if (studyRepository.findStudyByCaseId(caseId).isEmpty() || !studyRepository.isStudyOwner(caseId, principal)) denyStudy(request, caseId);
    studyRepository.grantStudyAccess(caseId, principalId, permission, principal);
  }

  private void requireStudy(HttpServletRequest request, String caseId, boolean write) {
    if (studyRepository == null) return;
    if (caseId == null || caseId.isBlank()) denyStudy(request, "");
    if (studyRepository.findStudyByCaseId(caseId).isEmpty()) {
      // Las cargas iniciales reservan un caso nuevo creando un grant OWNER antes de que exista la fila del
      // catálogo de estudios. Ese único owner acotado puede continuar el workflow de upload;
      // los estudios históricos ausentes/sin asignar y los llamantes arbitrarios siguen fallando de forma cerrada.
      if (studyRepository.hasStudyAccess(caseId, principal(request), write)) return;
      denyStudy(request, caseId);
    }
    if (!studyRepository.hasStudyAccess(caseId, principal(request), write)) denyStudy(request, caseId);
  }

  private String principal(HttpServletRequest request) {
    TokenService.Claims claims = claims(request);
    if (claims != null && claims.subject() != null && !claims.subject().isBlank()) return claims.subject();
    Object actor = request.getAttribute(AuthFilter.ACTOR_ID_ATTRIBUTE);
    if (actor instanceof String value && !value.isBlank()) return value;
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Autenticacion requerida.");
  }

  private void denyStudy(HttpServletRequest request, String caseId) {
    TokenService.Claims claims = claims(request);
    auditDenied(request, claims, caseId, "STUDY_ACCESS", claims == null ? List.of() : claims.roles());
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Estudio inexistente.");
  }

  public void requireAdmin(HttpServletRequest request, String entityId) {
    requireAnyRole(request, entityId, "ADMIN", List.of("ADMIN"));
  }

  public void requireProfessional(HttpServletRequest request, String entityId) {
    requireAnyRole(request, entityId, "PROFESSIONAL", List.of("REVIEWER", "DOCTOR", "ADMIN"));
  }

  private void requireAnyRole(
      HttpServletRequest request, String entityId, String semanticRole, List<String> allowedRoles) {
    TokenService.Claims claims = claims(request);
    List<String> currentRoles =
        claims == null || claims.roles() == null ? List.of() : claims.roles();
    boolean allowed = currentRoles.stream().anyMatch(allowedRoles::contains);
    if (allowed) {
      return;
    }
    auditDenied(request, claims, entityId, semanticRole, currentRoles);
    throw new ResponseStatusException(
        HttpStatus.FORBIDDEN, "No tiene permisos para realizar esta operacion.");
  }

  private TokenService.Claims claims(HttpServletRequest request) {
    Object value = request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE);
    return value instanceof TokenService.Claims claims ? claims : null;
  }

  private void auditDenied(
      HttpServletRequest request,
      TokenService.Claims claims,
      String entityId,
      String semanticRole,
      List<String> currentRoles) {
    if (auditService == null) return;
    try {
      auditService.record(
          claims == null || claims.subject().isBlank() ? "anonymous" : claims.subject(),
          "access.denied",
          entityId,
          traceId(request),
          Map.of(
              "requiredRole", semanticRole,
              "roles", currentRoles,
              "method", request.getMethod()));
    } catch (RuntimeException ignored) {
      // El resultado de la autorización no debe depender de la disponibilidad de la persistencia de auditoría.
    }
  }

  private String traceId(HttpServletRequest request) {
    Object attribute = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    if (attribute instanceof String value && !value.isBlank()) {
      return value;
    }
    String header = request.getHeader(TraceIdFilter.TRACE_ID_HEADER);
    return header == null || header.isBlank() ? "unavailable" : header;
  }
}
