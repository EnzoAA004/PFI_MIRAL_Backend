package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.domain.EvidenceItem;
import ar.edu.uade.pfi.backend.service.ConcordanceResult;
import ar.edu.uade.pfi.backend.service.ConcordanceRuleSetV1;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * EXT-01: stateless concordance evaluation — the caller supplies the already-normalized
 * {@link EvidenceItem}s it holds (from whichever real product surfaces produced them: a
 * disc-level batch classification, an on-demand subarticular-ROI classification, a measurement,
 * a professional review) and gets back {@link ConcordanceResult}s. Nothing here is persisted;
 * this never becomes a new source of truth, only a read-only computation over evidence the
 * caller already has.
 */
@RestController
public class ConcordanceController {
    private final ConcordanceRuleSetV1 ruleSet;
    private final RoleAuthorizationService authorizationService;

    public ConcordanceController(ConcordanceRuleSetV1 ruleSet, RoleAuthorizationService authorizationService) {
        this.ruleSet = ruleSet;
        this.authorizationService = authorizationService;
    }

    public record EvidenceItemRequestDto(
        String id, String source, String modelId, String pipelineVersion, String level, String side,
        String plane, String phenomenon, String finding, Double value, Double confidence,
        Boolean experimental, String reviewStatus
    ) {
        EvidenceItem toDomain(String caseId) {
            return new EvidenceItem(id, source, modelId, pipelineVersion, caseId, level, side, plane,
                phenomenon, finding, value, confidence, experimental != null && experimental, reviewStatus);
        }
    }

    public record ConcordanceRequestDto(List<EvidenceItemRequestDto> evidence) {
    }

    @PostMapping("/api/studies/{caseId}/analytics/concordance")
    public List<ConcordanceResult> evaluate(@PathVariable String caseId, @RequestBody ConcordanceRequestDto request, HttpServletRequest httpRequest) {
        authorizationService.requireProfessional(httpRequest, caseId);
        List<EvidenceItem> items = request.evidence() == null ? List.of() : request.evidence().stream().map(dto -> dto.toDomain(caseId)).toList();
        return ruleSet.evaluate(items);
    }
}
