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
 * EXT-04B2: the minimal, explicit capture point for AI-suggested geometry — deliberately NOT
 * wired into any controller or the {@code /v1/product/analyze} ingestion path yet.
 *
 * <p>The EXT-04B1 audit found that the geometry a caller would want to ingest here
 * ({@code PlaneMaskV2.geometry}, {@code PlaneSegmentationV2}, {@code PlaneLandmarkV2}) is
 * emitted by the AI Module's <em>multiplanar v2</em> pipeline ({@code pfi.multiplanar-run.v2}),
 * while the review/report system this evidence must plug into
 * ({@code AiDiscLevelReview}/{@code AiProductAnalysisRunReview}, and {@code StudyReportDto})
 * is anchored to a DIFFERENT, separate pipeline: {@code AiProductAnalysisRun}/
 * {@code AiDiscLevelResult} built from {@code POST /v1/product/analyze}
 * ({@code pfi.ai-product-result.v1}), whose {@code AiProductAnalyzeResponseDto} carries
 * <strong>no geometry at all</strong> (confirmed: {@code segmentation.maskClasses} is a class
 * list, {@code morphometry} is a count, {@code measurements[]} are pure scalars).
 *
 * <p>Bridging those two pipelines automatically (matching a multiplanar-v2 plane run to an
 * {@code AiProductAnalysisRun}/{@code AiDiscLevelResult} by plane+level+slice) is a real,
 * separate piece of work this "Foundation" iteration does not attempt — doing so without a
 * demonstrated, tested mapping would risk silently pairing the wrong geometry with the wrong
 * finding. This is the contradiction flagged before writing the migration (see
 * docs/extensions/EXT_04B_BACKEND_FOUNDATION.md, "decisiones a revisar antes de frontend").
 *
 * <p>What this class DOES provide, per EXT-04B2's explicit ask ("implementar el punto mínimo de
 * captura/normalización necesario"): a single, narrow, already-guarded, already-tested method
 * that CAN persist AI-origin geometry against an existing {@code AiProductAnalysisRun}/
 * {@code AiDiscLevelResult} once the caller has already resolved which plane-run geometry
 * belongs to which disc level — the caller supplies the already-normalized 0..256 geometry (via
 * {@link ar.edu.uade.pfi.backend.domain.EvidenceCoordinateNormalizer} beforehand) plus the AI
 * governance flags this method refuses to bypass.
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
     * Request shape mirrors {@link AiEvidenceRegion}'s own fields for the {@code origin="ai"}
     * case, minus the ones this service fills in itself ({@code id}, {@code origin},
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
        /** Only set when this specific disc level's {@code degenerativeFindingsStatus} is known to the caller. */
        String degenerativeFindingsStatus,
        /** Only set when this specific disc level's {@code axialPairingExperimental} is known to the caller. */
        boolean axialPairingExperimental
    ) {
    }

    /**
     * Persists one AI-origin {@link AiEvidenceRegion}, refusing to ingest geometry from a
     * capability the EXT-04B1 audit found blocked (P10.7) or from an experimental capability
     * (67C) not flagged as such.
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
