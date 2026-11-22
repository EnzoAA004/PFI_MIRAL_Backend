package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.client.AiProductCheckpointClient;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import ar.edu.uade.pfi.backend.dto.DiscDegenerativeProductRequestDto;
import ar.edu.uade.pfi.backend.dto.DiscSegmentationSourceDto;
import ar.edu.uade.pfi.backend.dto.FullSeriesSegmentationRequestDto;
import ar.edu.uade.pfi.backend.service.DiscDegenerativeFindingsPersistenceService;
import ar.edu.uade.pfi.backend.util.DiscDegenerativeFindingsPublicProjection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Checkpoint P10.9 orientado al backend. No se requiere ningún frontend para ejercitar estas rutas. */
@RestController
@RequestMapping("/api/ai/v2/product")
public class AiProductCheckpointController {
  private static final Set<String> PLANES = Set.of("sagittal", "axial");
  private static final Set<String> MODEL_BY_PLANE = Set.of("sagittal_spider", "axial_t2_alkafri");
  private static final Set<String> SOURCE_ROLES = Set.of("sagittal_t1", "sagittal_t2");

  private final AiProductCheckpointClient aiClient;
  private final DiscDegenerativeFindingsPersistenceService persistence;
  private final RoleAuthorizationService authorization;
  private final StudyRepository studyRepository;

  public AiProductCheckpointController(
      AiProductCheckpointClient aiClient, DiscDegenerativeFindingsPersistenceService persistence) {
    this.aiClient = aiClient;
    this.persistence = persistence;
    this.authorization = null;
    this.studyRepository = null;
  }

  public AiProductCheckpointController(
      AiProductCheckpointClient aiClient, DiscDegenerativeFindingsPersistenceService persistence,
      RoleAuthorizationService authorization) {
    this.aiClient = aiClient;
    this.persistence = persistence;
    this.authorization = authorization;
    this.studyRepository = null;
  }

  @Autowired
  public AiProductCheckpointController(
      AiProductCheckpointClient aiClient,
      DiscDegenerativeFindingsPersistenceService persistence,
      RoleAuthorizationService authorization,
      StudyRepository studyRepository) {
    this.aiClient = aiClient;
    this.persistence = persistence;
    this.authorization = authorization;
    this.studyRepository = studyRepository;
  }

  @GetMapping("/checkpoint")
  public Map<String, Object> checkpoint(HttpServletRequest request) {
    authorization.requireAdmin(request, "ai.product.checkpoint");
    Map<String, Object> result = new LinkedHashMap<>(aiClient.productCheckpoint());
    result.put(
        "backendContract",
        Map.of(
            "schemaVersion", "pfi.p10-9.backend-product-checkpoint.v1",
            "p10_7PredictionPersistence", "immutable_metrics_snapshot",
            "reviewStoredSeparately", true,
            "frontendRequiredForValidation", false,
            "humanReviewRequired", true,
            "notClinicalDiagnosis", true));
    return result;
  }

  @PostMapping("/series-segmentation")
  public Map<String, Object> runFullSeries(@RequestBody FullSeriesSegmentationRequestDto request, HttpServletRequest httpRequest) {
    requireFullSeriesRequest(request);
    authorization.requireStudyWrite(httpRequest, request.caseId());
    authorization.requireResourceBelongsToStudy(httpRequest, request.inputId(), request.caseId(), true);
    Map<String, Object> response = aiClient.runFullSeriesSegmentation(request);
    requireFullSeriesResponse(response);
    if (studyRepository == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "El registro de corridas no está disponible.");
    }
    String runId = (String) response.get("runId");
    if (!studyRepository.registerAiPipelineRunResource(runId, request.caseId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No se pudo asociar la corrida al estudio.");
    }
    return response;
  }

  @GetMapping("/series-segmentation/{runId}/{plane}/slices/{index}/{assetName}")
  public ResponseEntity<byte[]> fullSeriesAsset(
      @PathVariable String runId,
      @PathVariable String plane,
      @PathVariable int index,
      @PathVariable String assetName,
      HttpServletRequest request) {
    if (authorization == null || studyRepository == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "La autorización de estudios no está disponible.");
    }
    String caseId = studyRepository.resolveStudyCaseId(runId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurso no encontrado."));
    authorization.requireResourceBelongsToStudy(request, runId, caseId, false);
    ResponseEntity<byte[]> upstream = aiClient.getFullSeriesAsset(runId, plane, index, assetName);
    byte[] body = upstream.getBody() == null ? new byte[0] : upstream.getBody();
    return ResponseEntity.status(upstream.getStatusCode())
        .contentType(MediaType.IMAGE_PNG)
        .cacheControl(CacheControl.noStore())
        .body(body);
  }

  @PostMapping("/disc-degenerative-findings")
  public Map<String, Object> predictDiscDegenerative(
      @RequestBody DiscDegenerativeProductRequestDto request, HttpServletRequest httpRequest) {
    requireDiscRequest(request);
    authorization.requireStudyWrite(httpRequest, request.caseId());
    authorization.requireResourceBelongsToStudy(
        httpRequest, request.multiplanarRunId(), request.caseId(), true);
    for (DiscSegmentationSourceDto source : request.sources()) {
      authorization.requireResourceBelongsToStudy(
          httpRequest, source.inputId(), request.caseId(), true);
      authorization.requireResourceBelongsToStudy(
          httpRequest, source.segmentationRunId(), request.caseId(), true);
    }
    return predictDiscDegenerativeInternal(request);
  }

  Map<String, Object> predictDiscDegenerative(DiscDegenerativeProductRequestDto request) {
    requireDiscRequest(request);
    return predictDiscDegenerativeInternal(request);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> predictDiscDegenerativeInternal(DiscDegenerativeProductRequestDto request) {
    Map<String, Object> prediction = aiClient.predictDiscDegenerativeFromSegmentation(request);
    // Persistir la predicción upstream completa, probabilidades incluidas: la validación,
    // la trazabilidad y la auditoría las necesitan. Solo la respuesta pública de abajo se
    // proyecta -- persistImmutable nunca recibe una copia sanitizada.
    persistence.persistImmutable(request.multiplanarRunId(), prediction);

    Map<String, Object> result = new LinkedHashMap<>(prediction);
    Object discFindings = result.get("discDegenerativeFindings");
    if (discFindings instanceof Map<?, ?> map) {
      result.put(
          "discDegenerativeFindings",
          DiscDegenerativeFindingsPublicProjection.sanitize((Map<String, Object>) map));
    }
    result.put(
        "persistence",
        Map.of(
            "status",
            "persisted_immutable",
            "multiplanarRunId",
            request.multiplanarRunId(),
            "reviewStoredSeparately",
            true));
    return result;
  }

  private void requireFullSeriesRequest(FullSeriesSegmentationRequestDto request) {
    if (request == null
        || blank(request.caseId())
        || blank(request.inputId())
        || blank(request.plane())
        || blank(request.modelKey())) {
      throw badRequest("Solicitud de segmentacion de serie invalida.");
    }
    if (!PLANES.contains(request.plane())) throw badRequest("plane invalido.");
    if (!MODEL_BY_PLANE.contains(request.modelKey())) throw badRequest("modelKey invalido.");
    if ("sagittal".equals(request.plane()) && !"sagittal_spider".equals(request.modelKey())) {
      throw badRequest("modelKey no corresponde al plano.");
    }
    if ("axial".equals(request.plane()) && !"axial_t2_alkafri".equals(request.modelKey())) {
      throw badRequest("modelKey no corresponde al plano.");
    }
  }

  @SuppressWarnings("unchecked")
  private void requireFullSeriesResponse(Map<String, Object> response) {
    if (response == null
        || !"pfi.full-series-segmentation.v1".equals(response.get("schemaVersion"))
        || !"completed".equals(response.get("status"))
        || !Boolean.TRUE.equals(response.get("coverageComplete"))
        || !Boolean.TRUE.equals(response.get("humanReviewRequired"))
        || !Boolean.TRUE.equals(response.get("notClinicalDiagnosis"))
        || !(response.get("runId") instanceof String runId)
        || blank(runId)) {
      throw invalidUpstream();
    }
    Object sliceCount = response.get("sliceCount");
    Object segmented = response.get("segmentedSliceCount");
    if (!(sliceCount instanceof Number total)
        || !(segmented instanceof Number done)
        || total.intValue() <= 0
        || total.intValue() != done.intValue()) {
      throw invalidUpstream();
    }
    Object slicesRaw = response.get("slices");
    if (!(slicesRaw instanceof List<?> slices) || slices.size() != total.intValue())
      throw invalidUpstream();
  }

  private void requireDiscRequest(DiscDegenerativeProductRequestDto request) {
    if (request == null || blank(request.multiplanarRunId()) || blank(request.caseId())) {
      throw badRequest("Solicitud P10.7 invalida.");
    }
    List<DiscSegmentationSourceDto> sources = request.sources();
    if (sources == null || sources.isEmpty() || sources.size() > 2)
      throw badRequest("sources invalido.");
    Set<String> seen = new java.util.HashSet<>();
    for (DiscSegmentationSourceDto source : sources) {
      if (source == null
          || !SOURCE_ROLES.contains(source.role())
          || blank(source.inputId())
          || blank(source.segmentationRunId())
          || !seen.add(source.role())) {
        throw badRequest("sources invalido.");
      }
    }
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private ResponseStatusException invalidUpstream() {
    return new ResponseStatusException(
        HttpStatus.BAD_GATEWAY, "El modulo de IA devolvio un contrato P10.9 invalido.");
  }
}
