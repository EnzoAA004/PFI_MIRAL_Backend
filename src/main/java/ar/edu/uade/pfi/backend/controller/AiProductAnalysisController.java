package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.dto.AiProductAnalysisApiRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisHistoryResponseDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisResultDto;
import ar.edu.uade.pfi.backend.service.AiProductAnalysisPersistenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 75-H / 76-M-O. The Frontend never sees the AI Module's URL, its resolved inputIds, or any
 * model SHA — it only sees this Backend-owned sub-resource of a study, addressed by
 * {@code caseId}.
 */
@RestController
@RequestMapping("/api/studies/{caseId}")
public class AiProductAnalysisController {
    private final AiProductAnalysisPersistenceService persistenceService;

    public AiProductAnalysisController(AiProductAnalysisPersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    /**
     * Stage 76-M: the request body no longer needs {@code sagittalInputId}/{@code axialInputId} —
     * the Backend resolves both from the Study's own registered inputs. The fields are still
     * accepted on the wire (deprecated) but have no effect.
     */
    @PostMapping("/product-analysis")
    public AiProductAnalysisResultDto analyze(@PathVariable String caseId, @RequestBody(required = false) AiProductAnalysisApiRequestDto request) {
        AiProductAnalysisApiRequestDto body = request == null ? new AiProductAnalysisApiRequestDto(null, null, null) : request;
        return persistenceService.analyze(caseId, body.metadata());
    }

    @GetMapping("/product-analyses")
    public AiProductAnalysisHistoryResponseDto history(@PathVariable String caseId) {
        return new AiProductAnalysisHistoryResponseDto(caseId, persistenceService.history(caseId));
    }

    @GetMapping("/product-analyses/{analysisId}")
    public AiProductAnalysisResultDto detail(@PathVariable String caseId, @PathVariable String analysisId) {
        return persistenceService.detail(caseId, analysisId);
    }
}
