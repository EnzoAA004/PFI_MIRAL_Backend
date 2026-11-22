package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.domain.FindingDecision;
import ar.edu.uade.pfi.backend.service.FindingDecisionService;
import ar.edu.uade.pfi.backend.web.filter.TraceIdFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Decisiones del profesional (aceptado / rechazado / dudoso) sobre los hallazgos de la corrida. Rol profesional en todos
 * los endpoints; el GET no escribe y el PUT escribe solo la decision del hallazgo indicado.
 */
@RestController
@RequestMapping("/api/studies/{caseId}/finding-decisions")
public class FindingDecisionController {
    private final FindingDecisionService service;
    private final RoleAuthorizationService authorizationService;

    public FindingDecisionController(FindingDecisionService service, RoleAuthorizationService authorizationService) {
        this.service = service;
        this.authorizationService = authorizationService;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DecideRequest(String findingKey, String decision, String note) {
    }

    @GetMapping
    public List<FindingDecision> list(@PathVariable String caseId, @RequestParam(required = false) String runId, HttpServletRequest request) {
        authorizationService.requireProfessional(request, caseId);
        authorizationService.requireStudyRead(request, caseId);
        return service.list(caseId, runId);
    }

    @PutMapping
    public List<FindingDecision> decide(@PathVariable String caseId, @RequestParam(required = false) String runId, @RequestBody DecideRequest body, HttpServletRequest request) {
        authorizationService.requireProfessional(request, caseId);
        authorizationService.requireStudyWrite(request, caseId);
        return service.decide(caseId, runId, body.findingKey(), body.decision(), body.note(), actor(request), traceId(request));
    }

    private String actor(HttpServletRequest request) {
        Object claims = request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE);
        return claims instanceof TokenService.Claims c && c.subject() != null ? c.subject() : "";
    }

    private String traceId(HttpServletRequest request) {
        Object attribute = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (attribute instanceof String value && !value.isBlank()) return value;
        String header = request.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        return header == null || header.isBlank() ? null : header;
    }
}
