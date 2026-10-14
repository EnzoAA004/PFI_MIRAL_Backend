package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.dto.InitialAnalysisComparisonDto;
import ar.edu.uade.pfi.backend.service.InitialAnalysisComparisonService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Comparación de mediciones del análisis inicial (real) entre dos estudios del mismo paciente.
 *
 * <p>Solo lectura, pero exige el rol profesional: entrega valores de mediciones de dos estudios. La
 * pertenencia al mismo paciente la valida el servicio; el acceso a ambos estudios queda cubierto por el
 * mismo rol (no existe propiedad de estudios por usuario en este dominio).
 */
@RestController
@RequestMapping("/api/studies/{caseId}/initial-analysis-comparison")
public class InitialAnalysisComparisonController {
    private final InitialAnalysisComparisonService service;
    private final RoleAuthorizationService authorizationService;

    public InitialAnalysisComparisonController(InitialAnalysisComparisonService service, RoleAuthorizationService authorizationService) {
        this.service = service;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    public InitialAnalysisComparisonDto compare(
        @PathVariable String caseId,
        @RequestParam String referenceCaseId,
        @RequestParam(required = false) String currentRunId,
        @RequestParam(required = false) String referenceRunId,
        HttpServletRequest request
    ) {
        authorizationService.requireProfessional(request, caseId);
        return service.compare(caseId, referenceCaseId, currentRunId, referenceRunId);
    }

    @GetMapping("/candidates")
    public InitialAnalysisComparisonDto.Candidates candidates(
        @PathVariable String caseId,
        @RequestParam(required = false) String currentRunId,
        HttpServletRequest request
    ) {
        authorizationService.requireProfessional(request, caseId);
        return service.candidates(caseId, currentRunId);
    }
}
