package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import ar.edu.uade.pfi.backend.domain.EvidenceIngestionGuard;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * EXT-04B2: el punto de captura mínimo y explícito para geometría sugerida por la IA — deliberadamente NO
 * conectado todavía a ningún controller ni al camino de ingesta de {@code /v1/product/analyze}.
 *
 * <p>La auditoría EXT-04B1 encontró que la geometría que alguien querría ingerir acá
 * ({@code PlaneMaskV2.geometry}, {@code PlaneSegmentationV2}, {@code PlaneLandmarkV2}) la
 * emite el pipeline <em>multiplanar v2</em> del módulo de IA ({@code pfi.multiplanar-run.v2}),
 * mientras que el sistema de revisión/reporte al que debe conectarse esta evidencia
 * ({@code AiDiscLevelReview}/{@code AiProductAnalysisRunReview}, y {@code StudyReportDto})
 * está anclado a un pipeline DISTINTO y separado: {@code AiProductAnalysisRun}/
 * {@code AiDiscLevelResult} construidos a partir de {@code POST /v1/product/analyze}
 * ({@code pfi.ai-product-result.v1}), cuyo {@code AiProductAnalyzeResponseDto} no lleva
 * <strong>ninguna geometría</strong> (confirmado: {@code segmentation.maskClasses} es una lista de
 * clases, {@code morphometry} es un conteo, {@code measurements[]} son escalares puros).
 *
 * <p>Unir esos dos pipelines automáticamente (emparejando una corrida de plano multiplanar-v2 con un
 * {@code AiProductAnalysisRun}/{@code AiDiscLevelResult} por plano+nivel+slice) es un trabajo real y
 * separado que esta iteración "Foundation" no intenta — hacerlo sin un
 * mapeo demostrado y testeado correría el riesgo de emparejar en silencio la geometría equivocada con el
 * hallazgo equivocado. Esta es la contradicción señalada antes de escribir la migración (ver
 * docs/extensions/EXT_04B_BACKEND_FOUNDATION.md, "decisiones a revisar antes de frontend").
 *
 * <p>Lo que esta clase SÍ provee, según el pedido explícito de EXT-04B2 ("implementar el punto mínimo de
 * captura/normalización necesario"): un único método, acotado, ya protegido y ya testeado
 * que PUEDE persistir geometría de origen IA contra un {@code AiProductAnalysisRun}/
 * {@code AiDiscLevelResult} existente una vez que quien llama ya resolvió qué geometría de plano-corrida
 * pertenece a qué nivel de disco — quien llama provee la geometría 0..256 ya normalizada (mediante
 * {@link ar.edu.uade.pfi.backend.domain.EvidenceCoordinateNormalizer} de antemano) más los flags de
 * gobernanza de IA que este método se niega a eludir.
 */
@Component
public class EvidenceRegionIngestionService {
    private final StudyRepository repository;
    private final Clock clock;

    @Autowired
    public EvidenceRegionIngestionService(StudyRepository repository) {
        this(repository, Clock.systemUTC());
    }

    EvidenceRegionIngestionService(StudyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * La forma del request replica los campos propios de {@link AiEvidenceRegion} para el caso {@code origin="ai"},
     * menos los que este servicio completa por sí mismo ({@code id}, {@code origin},
     * {@code createdAt}).
     */
    public record IngestAiGeometryRequest(
        String analysisRunId,
        String discLevelResultId,
        String measurementId,
        String plane,
        String level,
        String structure,
        Integer sliceIndex,
        String geometryType,
        Map<String, Object> geometry,
        Map<String, Object> coordinateSpaceRef,
        String modelId,
        String modelSha256,
        String pipelineVersion,
        Double confidence,
        boolean experimental,
        String label,
        String comment,
        /** Solo se setea cuando el {@code degenerativeFindingsStatus} de este nivel de disco específico es conocido por quien llama. */
        String degenerativeFindingsStatus,
        /** Solo se setea cuando el {@code axialPairingExperimental} de este nivel de disco específico es conocido por quien llama. */
        boolean axialPairingExperimental
    ) {
    }

    /**
     * Persiste una {@link AiEvidenceRegion} de origen IA, negándose a ingerir geometría de una
     * capacidad que la auditoría EXT-04B1 encontró bloqueada (P10.7) o de una capacidad experimental
     * (67C) no marcada como tal.
     */
    public AiEvidenceRegion ingestAiGeometry(IngestAiGeometryRequest request) {
        EvidenceIngestionGuard.requireNotBlockedPathologyInference(request.degenerativeFindingsStatus());
        EvidenceIngestionGuard.requireExperimentalFlagForAxialPairing(request.axialPairingExperimental(), request.experimental());

        AiEvidenceRegion region = new AiEvidenceRegion(
            UUID.randomUUID().toString(),
            request.analysisRunId(),
            request.discLevelResultId(),
            request.measurementId(),
            request.plane(),
            request.level(),
            request.structure(),
            request.sliceIndex(),
            "ai",
            request.geometryType(),
            request.geometry(),
            request.coordinateSpaceRef(),
            request.modelId(),
            request.modelSha256(),
            request.pipelineVersion(),
            request.confidence(),
            request.experimental(),
            request.label(),
            request.comment(),
            null,
            Instant.now(clock)
        );
        return repository.saveAiEvidenceRegion(region);
    }
}
