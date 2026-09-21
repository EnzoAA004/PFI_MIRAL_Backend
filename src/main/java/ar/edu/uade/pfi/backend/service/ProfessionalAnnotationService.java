package ar.edu.uade.pfi.backend.service;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;
import ar.edu.uade.pfi.backend.service.exception.RunReviewException;

import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegionCorrection;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.EvidenceGeometry;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.CorrectEvidenceRegionRequestDto;
import ar.edu.uade.pfi.backend.dto.CreateProfessionalEvidenceRegionRequestDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * EXT-04B5: the two write operations {@link EvidenceRegionIngestionService} deliberately does
 * NOT cover (that class is for {@code origin="ai"} geometry from the internal multiplanar
 * pipeline — a separate, still-unwired concern). This service is the professional-facing half:
 * creating a professional-authored region, and appending a correction to an existing one
 * (AI-origin or professional-origin). Review (accept/observe/reject) is deliberately NOT
 * duplicated here — {@code AiProductAnalysisController#reviewDiscLevel}/{@code #reviewRun}
 * already cover it at exactly the granularity an evidence region's own review status already
 * resolves from ({@link EffectiveAnnotationResolver}), so a region is reviewed through those
 * existing, already-tested endpoints, never a new one.
 *
 * <p>{@code origin} is never accepted from the caller: professional creation hardcodes
 * {@code "professional"} here, server-side, exactly like {@link EvidenceRegionIngestionService}
 * hardcodes {@code "ai"} for its own case — a client can never fabricate either origin through
 * either endpoint.
 */
@Service
public class ProfessionalAnnotationService {
    private final StudyRepository repository;
    private final EffectiveAnnotationResolver annotationResolver;
    private final Clock clock;

    @Autowired
    public ProfessionalAnnotationService(StudyRepository repository, EffectiveAnnotationResolver annotationResolver) {
        this(repository, annotationResolver, Clock.systemUTC());
    }

    ProfessionalAnnotationService(StudyRepository repository, EffectiveAnnotationResolver annotationResolver, Clock clock) {
        this.repository = repository;
        this.annotationResolver = annotationResolver;
        this.clock = clock;
    }

    public AiEvidenceRegion createProfessionalRegion(String caseId, String analysisRunId, CreateProfessionalEvidenceRegionRequestDto request, String authoredBy) {
        AiProductAnalysisRun run = requireRunOfStudy(caseId, analysisRunId);
        requireNotMaskReference(request.geometryType());

        // AiEvidenceRegion's own compact constructor validates geometryType/geometry (via
        // EvidenceGeometry.fromMap: 0..256 range, polygon >=3 points, bbox/ellipse positive
        // size) and plane/sliceIndex — never re-validated here, never bypassed either.
        AiEvidenceRegion region = new AiEvidenceRegion(
            UUID.randomUUID().toString(),
            run.id(),
            null,
            null,
            request.plane(),
            request.level(),
            request.structure(),
            request.sliceIndex(),
            "professional",
            request.geometryType(),
            request.geometry(),
            null,
            null,
            null,
            null,
            null,
            false,
            request.label(),
            request.note(),
            authoredBy,
            Instant.now(clock)
        );
        return repository.saveAiEvidenceRegion(region);
    }

    public AiEvidenceRegionCorrection correctRegion(String caseId, String analysisRunId, String regionId, CorrectEvidenceRegionRequestDto request, String reviewedBy) {
        AiProductAnalysisRun run = requireRunOfStudy(caseId, analysisRunId);
        requireNotMaskReference(request.geometryType());
        AiEvidenceRegion region = requireRegionOfRun(run, regionId);

        // Snapshot the CURRENT effective geometry (the latest correction's, if one already
        // exists — never the AI's original once superseded) as "originalGeometry" for this new
        // correction row — same "each correction remembers what it replaced" rule
        // AiMeasurementCorrection already follows, extended to a second-or-later correction.
        var effective = annotationResolver.effectiveAnnotations(run, true).stream()
            .filter(annotation -> annotation.region().id().equals(regionId))
            .findFirst()
            .orElseThrow(() -> new RunReviewException(HttpStatus.NOT_FOUND, "EVIDENCE_REGION_NOT_FOUND", "Región de evidencia no encontrada: " + regionId));

        AiEvidenceRegionCorrection correction = new AiEvidenceRegionCorrection(
            UUID.randomUUID().toString(),
            regionId,
            run.id(),
            effective.effectiveGeometryType(),
            effective.effectiveGeometry(),
            request.geometryType(),
            request.geometry(),
            request.comment(),
            reviewedBy,
            Instant.now(clock)
        );
        return repository.saveAiEvidenceRegionCorrection(correction);
    }

    private AiProductAnalysisRun requireRunOfStudy(String caseId, String analysisRunId) {
        Study study = repository.findStudyByCaseId(caseId).orElseThrow(() -> new StudyNotFoundException(caseId));
        List<AiProductAnalysisRun> runs = repository.findAiProductAnalysisRunsByStudyId(study.id());
        return runs.stream()
            .filter(run -> run.id().equals(analysisRunId))
            .findFirst()
            .orElseThrow(() -> new RunReviewException(HttpStatus.NOT_FOUND, "ANALYSIS_RUN_NOT_FOUND", "La corrida " + analysisRunId + " no pertenece al estudio " + caseId));
    }

    private AiEvidenceRegion requireRegionOfRun(AiProductAnalysisRun run, String regionId) {
        return repository.findAiEvidenceRegionsByAnalysisRunId(run.id()).stream()
            .filter(region -> region.id().equals(regionId))
            .findFirst()
            .orElseThrow(() -> new RunReviewException(HttpStatus.NOT_FOUND, "EVIDENCE_REGION_NOT_FOUND", "Región de evidencia no encontrada en esta corrida: " + regionId));
    }

    /**
     * MASK_REFERENCE is never creatable/correctable through this professional-facing service —
     * it points at an already-existing raster asset this service has no way to produce or
     * verify (Phase "MASK_REFERENCE: NO editable gráficamente").
     */
    private void requireNotMaskReference(String geometryType) {
        if (EvidenceGeometry.TYPE_MASK_REFERENCE.equals(geometryType)) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "MASK_REFERENCE_NOT_EDITABLE", "MASK_REFERENCE no es editable ni creable por esta vía.");
        }
    }
}
