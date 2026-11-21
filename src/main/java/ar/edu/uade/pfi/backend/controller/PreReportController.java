package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.dto.PreReportViewDto;
import ar.edu.uade.pfi.backend.service.PreReportService;
import ar.edu.uade.pfi.backend.web.filter.TraceIdFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Preinforme visual de la corrida que muestra el visor. Rol profesional en todos los endpoints. El GET no escribe; el PUT y
 * el POST escriben solo el borrador del preinforme: nunca la revisión, las correcciones ni las predicciones del estudio.
 */
@RestController
@RequestMapping("/api/studies/{caseId}/prereport")
public class PreReportController {
    private final PreReportService service;
    private final RoleAuthorizationService authorizationService;

    public PreReportController(PreReportService service, RoleAuthorizationService authorizationService) {
        this.service = service;
        this.authorizationService = authorizationService;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SaveRequest(Integer expectedVersion, Map<String, Object> edits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateSourcesRequest(Integer expectedVersion) {
    }

    @GetMapping
    public PreReportViewDto view(@PathVariable String caseId, @RequestParam(required = false) String runId, HttpServletRequest request) {
        authorizationService.requireProfessional(request, caseId);
        authorizationService.requireStudyRead(request, caseId);
        return service.view(caseId, runId);
    }

    @PutMapping
    public PreReportViewDto save(@PathVariable String caseId, @RequestParam(required = false) String runId, @RequestBody(required = false) SaveRequest body, HttpServletRequest request) {
        authorizationService.requireProfessional(request, caseId);
        authorizationService.requireStudyWrite(request, caseId);
        SaveRequest safe = body == null ? new SaveRequest(null, null) : body;
        return service.save(caseId, runId, safe.expectedVersion(), safe.edits(), actor(request), traceId(request));
    }

    @PostMapping("/update-sources")
    public PreReportViewDto updateSources(@PathVariable String caseId, @RequestParam(required = false) String runId, @RequestBody(required = false) UpdateSourcesRequest body, HttpServletRequest request) {
        authorizationService.requireProfessional(request, caseId);
        authorizationService.requireStudyWrite(request, caseId);
        return service.updateSources(caseId, runId, body == null ? null : body.expectedVersion(), actor(request), traceId(request));
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
