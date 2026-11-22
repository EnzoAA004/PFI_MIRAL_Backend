package ar.edu.uade.pfi.backend.service;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;

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
 * Etapa 76: orquesta una corrida de product-analysis para que el Frontend solo tenga que conocer {@code caseId}.
 *
 * <p>Secuencia: resolver los inputs a partir de las series registradas del propio Study
 * ({@link AiStudyInputResolver}) → llamar al módulo de IA ({@link AiAnalysisService}, que ya
 * corre la validación de contrato/seguridad de la Etapa 75) → mapear la respuesta validada a registros
 * estructurados → persistirlos en una transacción corta
 * ({@link StudyRepository#saveAiProductAnalysisRun}).
 *
 * <p>La llamada a la IA de ~180s ocurre por completo antes de que empiece cualquier escritura en la base de datos (etapa 76-K) — esta
 * clase no mantiene ninguna transacción de larga duración, y la única escritura es la llamada única, ya transaccional, al
 * repositorio al final.
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
        // Recién persistida: todavía no puede existir revisión/corrección para esta corrida.
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
     * Etapa 78: lookup compartido a prueba de IDOR — un {@code analysisId} que existe pero pertenece al
     * {@code caseId} de otro Study devuelve 404 exactamente igual que uno que no existe en absoluto, sin
     * filtrar nunca la existencia entre Studies. Reutilizado por {@link AiProductAnalysisReviewService} para que los
     * endpoints de revisión/corrección apliquen la misma regla en lugar de una segunda copia.
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
