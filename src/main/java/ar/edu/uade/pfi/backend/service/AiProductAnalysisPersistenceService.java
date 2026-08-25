package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisResultDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stage 76: orchestrates a product-analysis run so the Frontend only has to know {@code caseId}.
 *
 * <p>Sequence: resolve inputs from the Study's own registered series
 * ({@link AiStudyInputResolver}) → call the AI Module ({@link AiAnalysisService}, which already
 * runs the Stage-75 contract/safety validation) → map the validated response into structured
 * records → persist them in one short transaction
 * ({@link StudyRepository#saveAiProductAnalysisRun}).
 *
 * <p>The ~180s AI call happens entirely before any database write starts (stage 76-K) — this
 * class holds no long-lived transaction, and the only write is the single, already-transactional
 * repository call at the end.
 */
@Service
public class AiProductAnalysisPersistenceService {
    private final AiAnalysisService analysisService;
    private final AiStudyInputResolver inputResolver;
    private final StudyRepository repository;
    private final AiProductAnalysisMapper mapper;

    public AiProductAnalysisPersistenceService(
        AiAnalysisService analysisService,
        AiStudyInputResolver inputResolver,
        StudyRepository repository,
        AiProductAnalysisMapper mapper
    ) {
        this.analysisService = analysisService;
        this.inputResolver = inputResolver;
        this.repository = repository;
        this.mapper = mapper;
    }

    public AiProductAnalysisResultDto analyze(String caseId, Map<String, Object> metadata) {
        AiStudyInputResolver.ResolvedInputs resolved = inputResolver.resolve(caseId);
        AiProductAnalyzeResponseDto response = analysisService.analyze(
            caseId,
            resolved.sagittalInputId(),
            resolved.axialInputId(),
            metadata
        );
        AiProductAnalysisMapper.Mapped mapped = mapper.toDomain(resolved.studyId(), response, resolved.sagittalInputId(), resolved.axialInputId());
        repository.saveAiProductAnalysisRun(mapped.run(), mapped.discLevels(), mapped.measurements());
        // Freshly persisted: no review/correction can exist yet for this run.
        return mapper.toResultDto(mapped.run(), mapped.discLevels(), mapped.measurements(), null, List.of(), List.of());
    }

    public List<AiProductAnalysisResultDto> history(String caseId) {
        String studyId = resolveStudyId(caseId);
        return repository.findAiProductAnalysisRunsByStudyId(studyId).stream()
            .map(this::hydrate)
            .toList();
    }

    public AiProductAnalysisResultDto detail(String caseId, String analysisId) {
        return hydrate(resolveAnalysisRun(caseId, analysisId));
    }

    /**
     * Stage 78: shared IDOR-safe lookup — an {@code analysisId} that exists but belongs to a
     * different Study's {@code caseId} 404s exactly like one that doesn't exist at all, never
     * leaking cross-Study existence. Reused by {@link AiProductAnalysisReviewService} so the
     * review/correction endpoints enforce the identical rule rather than a second copy of it.
     */
    public AiProductAnalysisRun resolveAnalysisRun(String caseId, String analysisId) {
        String studyId = resolveStudyId(caseId);
        return repository.findAiProductAnalysisRunById(analysisId)
            .filter(candidate -> candidate.studyId().equals(studyId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Analisis no encontrado"));
    }

    private String resolveStudyId(String caseId) {
        return repository.findStudyByCaseId(caseId)
            .orElseThrow(() -> new StudyNotFoundException(caseId))
            .id();
    }

    private AiProductAnalysisResultDto hydrate(AiProductAnalysisRun run) {
        return mapper.toResultDto(
            run,
            repository.findAiDiscLevelResultsByAnalysisRunId(run.id()),
            repository.findAiMeasurementResultsByAnalysisRunId(run.id()),
            repository.findAiProductAnalysisRunReview(run.id()).orElse(null),
            repository.findAiDiscLevelReviewsByAnalysisRunId(run.id()),
            repository.findAiMeasurementCorrectionsByAnalysisRunId(run.id())
        );
    }
}
