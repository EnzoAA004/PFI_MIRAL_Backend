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

/** Applies the study boundary to every case-addressed route, including controllers added later. */
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

    // The dedicated legacy-assignment route performs its own ADMIN authorization and is the
    // only allowed way to establish an initial owner for a persisted, unowned Study.
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
