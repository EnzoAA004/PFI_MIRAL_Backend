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
 * EXT-04B5: las dos operaciones de escritura que {@link EvidenceRegionIngestionService} deliberadamente
 * NO cubre (esa clase es para geometría {@code origin="ai"} del pipeline
 * multiplanar interno — una preocupación separada, todavía sin conectar). Este servicio es la mitad orientada al profesional:
 * crear una región de autoría profesional, y agregar una corrección a una existente
 * (de origen IA o profesional). La revisión (aceptar/observar/rechazar) deliberadamente NO se
 * duplica acá — {@code AiProductAnalysisController#reviewDiscLevel}/{@code #reviewRun}
 * ya la cubren exactamente con la granularidad desde la que ya se resuelve el estado de revisión propio de una región de evidencia
 * ({@link EffectiveAnnotationResolver}), así que una región se revisa mediante esos
 * endpoints existentes y ya testeados, nunca uno nuevo.
 *
 * <p>{@code origin} nunca se acepta de quien llama: la creación profesional hardcodea
 * {@code "professional"} acá, del lado del servidor, igual que {@link EvidenceRegionIngestionService}
 * hardcodea {@code "ai"} para su propio caso — un cliente nunca puede fabricar ninguno de los dos orígenes a través de
 * ninguno de los dos endpoints.
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

        // El compact constructor propio de AiEvidenceRegion valida geometryType/geometry (vía
        // EvidenceGeometry.fromMap: rango 0..256, polígono >=3 puntos, bbox/elipse de tamaño
        // positivo) y plane/sliceIndex — nunca se revalida acá, ni tampoco se elude.
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

        // Tomar una instantánea de la geometría efectiva ACTUAL (la de la última corrección, si ya
        // existe una — nunca la original de la IA una vez reemplazada) como "originalGeometry" de esta nueva
        // fila de corrección — la misma regla de "cada corrección recuerda lo que reemplazó"
        // que AiMeasurementCorrection ya sigue, extendida a una segunda corrección o posterior.
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
     * MASK_REFERENCE nunca se puede crear/corregir a través de este servicio orientado al profesional —
     * apunta a un asset raster ya existente que este servicio no tiene forma de producir ni
     * verificar (Fase "MASK_REFERENCE: NO editable gráficamente").
     */
    private void requireNotMaskReference(String geometryType) {
        if (EvidenceGeometry.TYPE_MASK_REFERENCE.equals(geometryType)) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "MASK_REFERENCE_NOT_EDITABLE", "MASK_REFERENCE no es editable ni creable por esta vía.");
        }
    }
}
