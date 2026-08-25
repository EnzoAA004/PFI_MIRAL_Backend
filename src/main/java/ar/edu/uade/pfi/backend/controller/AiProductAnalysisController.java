package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.dto.AiProductAnalysisApiRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import ar.edu.uade.pfi.backend.service.AiAnalysisService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 75-H. The Frontend never sees the AI Module's URL, its inputIds' internal resolution, or
 * any model SHA — it only sees this Backend-owned sub-resource of a study.
 */
@RestController
@RequestMapping("/api/studies/{caseId}/product-analysis")
public class AiProductAnalysisController {
    private final AiAnalysisService analysisService;

    public AiProductAnalysisController(AiAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping
    public AiProductAnalyzeResponseDto analyze(@PathVariable String caseId, @RequestBody(required = false) AiProductAnalysisApiRequestDto request) {
        AiProductAnalysisApiRequestDto body = request == null ? new AiProductAnalysisApiRequestDto(null, null, null) : request;
        return analysisService.analyze(caseId, body.sagittalInputId(), body.axialInputId(), body.metadata());
    }
}
