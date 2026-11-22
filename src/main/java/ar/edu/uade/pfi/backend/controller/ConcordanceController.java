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
 * EXT-01: evaluación de concordancia sin estado — quien llama aporta los {@link EvidenceItem}s ya normalizados
 * que posee (de cualquier superficie real del producto que los haya producido: una
 * clasificación por lote de niveles de disco, una clasificación de ROI subarticular a demanda, una medición,
 * una revisión profesional) y recibe de vuelta {@link ConcordanceResult}s. Nada acá se persiste;
 * esto nunca se convierte en una nueva fuente de verdad, solo en un cómputo de solo lectura sobre evidencia que
 * quien llama ya tiene.
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
