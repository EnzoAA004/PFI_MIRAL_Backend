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
 * EXT-04B5: professional creation of a new evidence region, and append-only correction of an
 * existing one (AI-origin or professional-origin). Review (accept/observe/reject) is
 * deliberately not duplicated here — see {@link ProfessionalAnnotationService}'s own javadoc for
 * why {@code AiProductAnalysisController#reviewDiscLevel}/{@code #reviewRun} already cover it.
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
     * Never trusts a client-supplied identity (same rule {@code AiProductAnalysisController}
     * already applies) — derived only from the authenticated principal's own JWT claims.
     */
    private String authoredBy(HttpServletRequest request) {
        Object value = request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE);
        if (!(value instanceof TokenService.Claims claims)) return null;
        if (claims.email() != null && !claims.email().isBlank()) return claims.email();
        if (claims.subject() != null && !claims.subject().isBlank()) return claims.subject();
        return null;
    }
}
