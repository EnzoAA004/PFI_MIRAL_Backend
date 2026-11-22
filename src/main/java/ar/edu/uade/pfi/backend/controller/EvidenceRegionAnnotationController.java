package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.dto.CorrectEvidenceRegionRequestDto;
import ar.edu.uade.pfi.backend.dto.CreateProfessionalEvidenceRegionRequestDto;
import ar.edu.uade.pfi.backend.dto.EvidenceRegionCorrectionResponseDto;
import ar.edu.uade.pfi.backend.dto.EvidenceRegionResponseDto;
import ar.edu.uade.pfi.backend.service.ProfessionalAnnotationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * EXT-04B5: creación profesional de una nueva región de evidencia, y corrección append-only de una
 * existente (de origen IA o profesional). La revisión (aceptar/observar/rechazar) deliberadamente
 * no se duplica acá — ver el javadoc propio de {@link ProfessionalAnnotationService} para saber
 * por qué {@code AiProductAnalysisController#reviewDiscLevel}/{@code #reviewRun} ya la cubren.
 */
@RestController
@RequestMapping("/api/studies/{caseId}/product-analyses/{analysisId}/evidence-regions")
public class EvidenceRegionAnnotationController {
    private final ProfessionalAnnotationService annotationService;
    private final RoleAuthorizationService authorizationService;

    public EvidenceRegionAnnotationController(ProfessionalAnnotationService annotationService, RoleAuthorizationService authorizationService) {
        this.annotationService = annotationService;
        this.authorizationService = authorizationService;
    }

    @PostMapping
    public EvidenceRegionResponseDto create(
        @PathVariable String caseId,
        @PathVariable String analysisId,
        @RequestBody CreateProfessionalEvidenceRegionRequestDto request,
        HttpServletRequest httpRequest
    ) {
        authorizationService.requireProfessional(httpRequest, analysisId);
        return EvidenceRegionResponseDto.from(
            annotationService.createProfessionalRegion(caseId, analysisId, request, authoredBy(httpRequest))
        );
    }

    @PostMapping("/{regionId}/corrections")
    public EvidenceRegionCorrectionResponseDto correct(
        @PathVariable String caseId,
        @PathVariable String analysisId,
        @PathVariable String regionId,
        @RequestBody CorrectEvidenceRegionRequestDto request,
        HttpServletRequest httpRequest
    ) {
        authorizationService.requireProfessional(httpRequest, analysisId);
        return EvidenceRegionCorrectionResponseDto.from(
            annotationService.correctRegion(caseId, analysisId, regionId, request, authoredBy(httpRequest))
        );
    }

    /**
     * Nunca confía en una identidad provista por el cliente (la misma regla que ya aplica {@code AiProductAnalysisController})
     * — se deriva únicamente de los claims JWT propios del principal autenticado.
     */
    private String authoredBy(HttpServletRequest request) {
        Object value = request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE);
        if (!(value instanceof TokenService.Claims claims)) return null;
        if (claims.email() != null && !claims.email().isBlank()) return claims.email();
        if (claims.subject() != null && !claims.subject().isBlank()) return claims.subject();
        return null;
    }
}
