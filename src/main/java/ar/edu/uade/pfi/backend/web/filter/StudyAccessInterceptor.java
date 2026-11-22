package ar.edu.uade.pfi.backend.web.filter;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.server.ResponseStatusException;

/** Aplica el límite de estudio a toda ruta direccionada por caso, incluidos los controllers que se agreguen más adelante. */
@Component
@ConditionalOnBean({RoleAuthorizationService.class, StudyRepository.class})
public class StudyAccessInterceptor implements HandlerInterceptor {
  private static final Set<String> RESOURCE_VARIABLES = Set.of("inputId", "runId", "planeRunId", "multiplanarRunId", "analysisId");
  private final RoleAuthorizationService authorization;
  private final StudyRepository repository;

  public StudyAccessInterceptor(RoleAuthorizationService authorization, StudyRepository repository) {
    this.authorization = authorization;
    this.repository = repository;
  }

  @Override
  @SuppressWarnings("unchecked")
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    Object value = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    Map<String, String> variables = value instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    String caseId = variables.get("caseId");
    boolean write = !HttpMethod.GET.matches(request.getMethod()) && !HttpMethod.HEAD.matches(request.getMethod());

    // La ruta dedicada de asignación legacy realiza su propia autorización ADMIN y es la
    // única forma permitida de establecer un owner inicial para un Study persistido y sin owner.
    if (request.getRequestURI().matches("/api/admin/studies/[^/]+/owner")) return true;

    if (caseId != null && !caseId.isBlank()) {
      check(request, caseId, write);
      String baseline = request.getParameter("baselineCaseId");
      if (baseline != null && !baseline.isBlank()) check(request, baseline, false);
      return true;
    }

    for (String variable : RESOURCE_VARIABLES) {
      String resourceId = variables.get(variable);
      if (resourceId == null || resourceId.isBlank()) continue;
      String resolvedCaseId = repository.resolveStudyCaseId(resourceId).orElse(null);
      if (resolvedCaseId == null) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Estudio inexistente.");
      check(request, resolvedCaseId, write);
      break;
    }
    if (request.getRequestURI().startsWith("/api/feedback-candidates/")) {
      String candidateId = variables.get("id");
      if (candidateId != null) {
        String resolvedCaseId = repository.resolveStudyCaseId(candidateId).orElse(null);
        if (resolvedCaseId == null) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Estudio inexistente.");
        check(request, resolvedCaseId, write);
      }
    }
    return true;
  }

  private void check(HttpServletRequest request, String caseId, boolean write) {
    if (write) authorization.requireStudyWrite(request, caseId);
    else authorization.requireStudyRead(request, caseId);
  }
}
