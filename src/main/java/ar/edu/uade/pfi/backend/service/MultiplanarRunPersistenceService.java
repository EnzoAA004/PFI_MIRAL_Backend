package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.StudyMetadataDto;
import ar.edu.uade.pfi.backend.dto.StudyUploadSeriesDto;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Persiste un CanonicalMultiplanarRun (producido por AiServiceClient para cualquiera de las dos versiones de contrato).
 * Acá no hay ramificación v1/v2: para cuando una corrida llega a este servicio ya fue
 * adaptada a la forma canónica, por lo que la lógica de persistencia es uniforme.
 */
@Service
public class MultiplanarRunPersistenceService {
  private static final String SNAPSHOT_SCHEMA_VERSION = "pfi.backend-run-snapshot.v2";

  private final StudyRunService studyRunService;
  private final RunAssetSnapshotService runAssetSnapshotService;
  private final Clock clock;

  @Autowired
  public MultiplanarRunPersistenceService(
      StudyRunService studyRunService, RunAssetSnapshotService runAssetSnapshotService) {
    this(studyRunService, runAssetSnapshotService, Clock.systemUTC());
  }

  public MultiplanarRunPersistenceService(StudyRunService studyRunService) {
    this(studyRunService, null, Clock.systemUTC());
  }

  MultiplanarRunPersistenceService(StudyRunService studyRunService, Clock clock) {
    this(studyRunService, null, clock);
  }

  MultiplanarRunPersistenceService(
      StudyRunService studyRunService,
      RunAssetSnapshotService runAssetSnapshotService,
      Clock clock) {
    this.studyRunService = studyRunService;
    this.runAssetSnapshotService = runAssetSnapshotService;
    this.clock = clock;
  }

  public void persistSuccessfulRun(
      MultiplanarRunRequestDto request, CanonicalMultiplanarRun response) {
    persistSuccessfulRun(request, null, response);
  }

  public StudyRunService.PreparedStudyMetadata prepareStudyMetadata(
      String caseId, StudyMetadataDto studyMetadata) {
    return studyRunService.prepareStudyMetadata(caseId, studyMetadata);
  }

  public void persistSuccessfulRun(
      MultiplanarRunRequestDto request,
      StudyMetadataDto studyMetadata,
      CanonicalMultiplanarRun response) {
    if (response == null || blank(response.multiplanarRunId())) return;
    Study study = studyRunService.upsertStudyMetadata(request.caseId(), studyMetadata);

    registerInput(study, "sagittal", request.sagittalInputId());
    registerInput(study, "axial", request.axialInputId());
    registerStudySeries(study, request);

    CanonicalPlaneRun sagittal = response.sagittal();
    CanonicalPlaneRun axial = response.axial();
    String studyRunId = UUID.randomUUID().toString();
    Instant now = clock.instant();

    var persistedRun =
        studyRunService.createRunWithId(
            studyRunId,
            study,
            response.multiplanarRunId(),
            valueOrEmpty(response.traceId()),
            valueOrEmpty(response.requestedInferenceMode()),
            valueOrDefault(response.effectiveInferenceMode(), response.requestedInferenceMode()),
            valueOrDefault(modelKey(sagittal), request.sagittalModelKey()),
            valueOrDefault(modelKey(axial), request.axialModelKey()),
            artifactHash(sagittal),
            artifactHash(axial),
            valueOrEmpty(planeRunId(sagittal)),
            valueOrEmpty(planeRunId(axial)),
            Map.of(),
            metricsSnapshot(response),
            artifacts(studyRunId, response, sagittal, axial, now),
            response.synthetic() ? "completed_synthetic" : "completed",
            "pending",
            "",
            null,
            "");
    if (runAssetSnapshotService != null) {
      boolean meshAvailable = runAssetSnapshotService.snapshot(persistedRun);
      if (!meshAvailable) {
        downgradeThreeDIfMeshUnavailable(persistedRun);
      }
    }
  }

  /**
   * El metricsSnapshot de la corrida (con threeD.enabled=true, calculado a partir de la propia
   * respuesta del módulo de IA) se persiste antes de que la malla del workspace se descargue/valide/almacene de verdad — esa
   * descarga todavía puede fallar después. Si falla, esto reescribe threeD a un estado bloqueado honesto
   * para que el backend nunca publique threeD.enabled=true con una URL que apunte a un
   * contenido no disponible (sin rollback de toda la corrida: los planos sagital/axial siguen siendo válidos y revisables).
   */
  @SuppressWarnings("unchecked")
  private void downgradeThreeDIfMeshUnavailable(
      ar.edu.uade.pfi.backend.domain.StudyRun persistedRun) {
    Object rawThreeD = persistedRun.metricsSnapshot().get("threeD");
    if (!(rawThreeD instanceof Map<?, ?> threeDMap)
        || !Boolean.TRUE.equals(threeDMap.get("enabled"))) return;
    Map<String, Object> blockedThreeD = new LinkedHashMap<>((Map<String, Object>) threeDMap);
    blockedThreeD.put("enabled", false);
    blockedThreeD.put("status", "experimental_blocked_insufficient_geometry");
    blockedThreeD.put("assets", List.of());
    Map<String, Object> updatedSnapshot = new LinkedHashMap<>(persistedRun.metricsSnapshot());
    updatedSnapshot.put("threeD", blockedThreeD);
    studyRunService.updateRunMetricsSnapshot(persistedRun.multiplanarRunId(), updatedSnapshot);
  }

  /**
   * Registra el resto de las series del estudio: aquellas sobre las que no corre ningún modelo.
   *
   * <p>Corre después de los dos planos analizados a propósito. Ambos escriben en la misma tabla con clave {@code
   * input_id}, así que una serie que además es un plano analizado llega acá en segundo lugar y su upsert completa
   * la descripción, la ponderación y la cantidad de slices que {@link #registerInput} no conoce —
   * en lugar de que la fila del plano gane y deje en blanco la entrada del catálogo.
   */
  private void registerStudySeries(Study study, MultiplanarRunRequestDto request) {
    List<StudyUploadSeriesDto> series = request.studySeries();
    if (series == null || series.isEmpty()) return;
    for (StudyUploadSeriesDto item : series) {
      if (item == null || blank(item.inputId())) continue;
      studyRunService.createInput(
          study,
          blank(item.plane()) ? "unknown" : item.plane(),
          item.inputId(),
          "ai-module-input",
          0,
          item.description() == null ? "" : item.description(),
          item.weighting() == null ? "" : item.weighting(),
          item.sliceCount() == null ? 0 : item.sliceCount(),
          item.multiplanar(),
          item.derived(),
          item.analyzable());
    }
  }

  private void registerInput(Study study, String plane, String inputId) {
    if (blank(inputId)) return;
    studyRunService.createInput(study, plane, inputId, "ai-module-input", 0);
  }

  private List<RunArtifact> artifacts(
      String studyRunId,
      CanonicalMultiplanarRun response,
      CanonicalPlaneRun sagittal,
      CanonicalPlaneRun axial,
      Instant now) {
    List<RunArtifact> artifacts = new ArrayList<>();
    addArtifacts(artifacts, studyRunId, "sagittal", sagittal, now);
    addArtifacts(artifacts, studyRunId, "axial", axial, now);
    addWorkspaceArtifacts(artifacts, studyRunId, response, now);
    return artifacts;
  }

  private void addArtifacts(
      List<RunArtifact> artifacts,
      String studyRunId,
      String plane,
      CanonicalPlaneRun planeRun,
      Instant now) {
    if (planeRun == null || blank(planeRun.planeRunId())) return;
    for (Map<String, Object> asset : planeRun.assets()) {
      if (!isValidAssetMetadata(asset, planeRun.planeRunId(), plane)) continue;
      String assetName = text(asset.get("assetName"));
      String contentType =
          blankOrDefault(text(asset.get("contentType")), "application/octet-stream");
      artifacts.add(
          new RunArtifact(
              UUID.randomUUID().toString(),
              studyRunId,
              planeRun.planeRunId(),
              plane,
              assetName,
              contentType,
              assetName,
              now));
    }
  }

  private void addWorkspaceArtifacts(
      List<RunArtifact> artifacts,
      String studyRunId,
      CanonicalMultiplanarRun response,
      Instant now) {
    Map<String, Object> threeD = response.threeD();
    Object assetsValue = threeD.get("assets");
    if (!(assetsValue instanceof List<?> assets)) return;
    for (Object value : assets) {
      if (!(value instanceof Map<?, ?> rawAsset)) continue;
      Map<String, Object> asset = objectMap(rawAsset);
      if (!isValidAssetMetadata(asset, response.multiplanarRunId(), "workspace")) continue;
      String assetName = text(asset.get("assetName"));
      String contentType =
          blankOrDefault(text(asset.get("contentType")), "application/octet-stream");
      artifacts.add(
          new RunArtifact(
              UUID.randomUUID().toString(),
              studyRunId,
              response.multiplanarRunId(),
              "workspace",
              assetName,
              contentType,
              assetName,
              now));
    }
  }

  /**
   * Aplica el contrato de metadata de assets v2 antes de confiar en él para la persistencia: generated debe ser
   * true, relativePath debe ser un path de asset relativo del mismo origen (nunca un path absoluto del filesystem,
   * un host ajeno ni un directory traversal) y debe referenciar el plano al que dice pertenecer.
   */
  private boolean isValidAssetMetadata(Map<String, Object> asset, String planeRunId, String plane) {
    String assetName = text(asset.get("assetName"));
    if (blank(assetName)) return false;
    if (!Boolean.TRUE.equals(asset.get("generated"))) return false;
    if (!isAllowedAsset(assetName, text(asset.get("contentType")))) return false;
    String relativePath = text(asset.get("relativePath"));
    if (blank(relativePath)) return false;
    String normalized = relativePath.toLowerCase(Locale.ROOT);
    if (normalized.contains("..")) return false;
    if (normalized.matches("^[a-z][a-z0-9+.-]*://.*")) return false;
    if (normalized.matches("^[a-z]:\\\\.*") || normalized.matches("^[a-z]:/.*")) return false;
    if (normalized.contains("localhost")
        || normalized.contains("cloudflare")
        || normalized.contains("host.docker.internal")) return false;
    if (!relativePath.contains(planeRunId)) return false;
    return relativePath.contains("/" + plane + "/" + assetName);
  }

  /**
   * Máscara de segmentación por clase ({@code mask-vertebra_group.png}). No puede vivir en una lista fija
   * porque las clases dependen del modelo. El patrón solo acepta la forma del nombre de una clase —
   * minúsculas, dígitos y guion bajo — por lo que no admite separadores de path ni extensiones arbitrarias.
   */
  private static final java.util.regex.Pattern CLASS_MASK_NAME =
      java.util.regex.Pattern.compile("^mask-[a-z][a-z0-9_]{0,31}\\.png$");

  private boolean isAllowedAsset(String assetName, String contentType) {
    if ("lumbar-3d-mesh.json".equals(assetName))
      return "application/json".equalsIgnoreCase(contentType);
    boolean known =
        List.of("input.png", "overlay.png", "mask-preview.png").contains(assetName)
            || CLASS_MASK_NAME.matcher(assetName).matches();
    return known && "image/png".equalsIgnoreCase(contentType);
  }

  private Map<String, Object> objectMap(Map<?, ?> raw) {
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      if (entry.getKey() instanceof String key) copy.put(key, entry.getValue());
    }
    return copy;
  }

  private Map<String, Object> metricsSnapshot(CanonicalMultiplanarRun response) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("schemaVersion", SNAPSHOT_SCHEMA_VERSION);
    snapshot.put("sourceSchemaVersion", valueOrEmpty(response.schemaVersion()));
    snapshot.put("status", valueOrEmpty(response.status()));
    snapshot.put("caseId", valueOrEmpty(response.caseId()));
    snapshot.put("workspaceMode", valueOrEmpty(response.workspaceMode()));
    snapshot.put("requestedInferenceMode", valueOrEmpty(response.requestedInferenceMode()));
    snapshot.put("effectiveInferenceMode", valueOrEmpty(response.effectiveInferenceMode()));
    snapshot.put("requestedPlanes", response.requestedPlanes());
    snapshot.put("completedPlanes", response.completedPlanes());
    snapshot.put("synthetic", response.synthetic());
    putIfPresent(snapshot, "fallbackReason", response.fallbackReason());
    snapshot.put("readiness", response.readiness());
    snapshot.put("governance", governanceMap(response.governance()));
    snapshot.put("threeD", response.threeD());
    snapshot.put("quality", response.quality());
    if (!response.degenerativeFindings().isEmpty()) {
      snapshot.put("degenerativeFindings", response.degenerativeFindings());
    }
    snapshot.put("review", response.review());
    Map<String, Object> planes = new LinkedHashMap<>();
    planes.put("sagittal", planeSnapshot(response.sagittal(), response.governance()));
    planes.put("axial", planeSnapshot(response.axial(), response.governance()));
    snapshot.put("planes", planes);
    return snapshot;
  }

  private Map<String, Object> planeSnapshot(
      CanonicalPlaneRun plane, CanonicalMultiplanarRun.Governance governance) {
    if (plane == null) return null;
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("planeRunId", plane.planeRunId());
    snapshot.put("plane", plane.plane());
    snapshot.put("status", plane.status());
    snapshot.put("effectiveInferenceMode", plane.effectiveInferenceMode());
    snapshot.put("synthetic", plane.synthetic());
    putIfPresent(snapshot, "fallbackReason", plane.fallbackReason());
    snapshot.put("model", plane.model());
    snapshot.put("input", plane.input());
    snapshot.put("coordinateSpace", plane.coordinateSpace());
    snapshot.put("series", plane.series());
    snapshot.put("masks", plane.masks());
    snapshot.put("landmarks", plane.landmarks());
    snapshot.put("measurements", transformMeasurements(plane));
    snapshot.put("segmentation", plane.segmentation());
    snapshot.put("quality", plane.quality());
    snapshot.put("humanReviewRequired", governance.humanReviewRequired());
    snapshot.put("notClinicalDiagnosis", governance.notClinicalDiagnosis());
    return snapshot;
  }

  private Map<String, Object> governanceMap(CanonicalMultiplanarRun.Governance governance) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("humanReviewRequired", governance.humanReviewRequired());
    map.put("notClinicalDiagnosis", governance.notClinicalDiagnosis());
    map.put("deidentified", governance.deidentified());
    map.put("diagnosisGenerated", governance.diagnosisGenerated());
    return map;
  }

  /**
   * Transforma la lista cruda de mediciones v2 del módulo de IA a la forma consumida por
   * worklist/history/frontend. Solo se transportan los campos que el módulo de IA efectivamente proveyó —
   * el nivel, los valores del revisor y las etiquetas clínicas (p. ej. "L4-L5") nunca se inventan acá. Las
   * correcciones del revisor viven por separado en domain_review_corrections.
   */
  private List<Map<String, Object>> transformMeasurements(CanonicalPlaneRun plane) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> measurement : plane.measurements()) {
      Object value = measurement.get("value");
      Map<String, Object> transformed = new LinkedHashMap<>();
      transformed.put("id", measurement.get("id"));
      transformed.put("labelKey", measurement.get("labelKey"));
      transformed.put("aiValue", value);
      transformed.put("value", value);
      transformed.put("unit", measurement.get("unit"));
      transformed.put("confidence", measurement.get("confidence"));
      transformed.put("source", "AI");
      transformed.put("status", measurement.get("status"));
      transformed.put("plane", plane.plane());
      transformed.put("level", vertebralLevel(measurement.get("level")));
      transformed.put(
          "levelScope", "study".equals(measurement.get("levelScope")) ? "study" : "level");
      transformed.put("points", measurement.getOrDefault("points", List.of()));
      transformed.put("experimental", Boolean.TRUE.equals(measurement.get("experimental")));
      transformed.put("detail", measurement.get("detail"));
      transformed.put("sliceIndex", measurement.get("sliceIndex"));
      transformed.put("measurementBasis", measurement.get("measurementBasis"));
      transformed.put(
          "linkedLandmarkIds", measurement.getOrDefault("linkedLandmarkIds", List.of()));
      result.add(transformed);
    }
    return result;
  }

  /**
   * Acepta el nivel de una medición solo cuando nombra un nivel vertebral.
   *
   * <p>Las corridas más viejas del módulo de IA ponían el plano ("sagittal") en este campo, que no es un nivel
   * vertebral en absoluto; dejar pasar eso archivaría cada medición bajo un nivel que no
   * existe.
   *
   * <p>El chequeo es sobre la forma y no sobre una lista fija de cinco espacios discales. Esa lista estaba
   * destruyendo en silencio datos que el módulo de IA había resuelto bien: un disco que el modelo identificó como T12-L1 y un
   * cuerpo vertebral que nombró L4 llegaban ambos acá y salían como null, así que el revisor veía "sin asignar"
   * — lo que se lee como "la IA no pudo distinguirlo" — para un nivel que en realidad había determinado. La forma
   * sigue rechazando "sagittal", que es para lo que existe este filtro.
   */
  private String vertebralLevel(Object value) {
    if (!(value instanceof String text)) return null;
    String normalized = text.trim().toUpperCase(Locale.ROOT);
    return VERTEBRAL_LEVEL.matcher(normalized).matches() ? normalized : null;
  }

  /** Un cuerpo vertebral ({@code L4}, {@code T12}, {@code S1}) o un espacio entre dos. */
  private static final java.util.regex.Pattern VERTEBRAL_LEVEL =
      java.util.regex.Pattern.compile("^[CTLS]\\d{1,2}(-[CTLS]\\d{1,2})?$");

  private String artifactHash(CanonicalPlaneRun plane) {
    return plane == null ? "" : text(plane.model().get("artifactHash"));
  }

  private void putIfPresent(Map<String, Object> target, String key, Object value) {
    if (value != null) target.put(key, value);
  }

  /**
   * La key del modelo vive bajo distintas keys del mapa según el contrato upstream: v2 guarda el nombre
   * wire "key" tal cual, v1 lo guarda como "modelKey".
   */
  private String modelKey(CanonicalPlaneRun plane) {
    if (plane == null) return "";
    Map<String, Object> model = plane.model();
    return text(model.containsKey("key") ? model.get("key") : model.get("modelKey"));
  }

  private String planeRunId(CanonicalPlaneRun plane) {
    return plane == null ? "" : valueOrEmpty(plane.planeRunId());
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private String blankOrDefault(String value, String fallback) {
    return blank(value) ? fallback : value;
  }

  private String valueOrDefault(String value, String fallback) {
    return blank(value) ? valueOrEmpty(fallback) : value.trim();
  }

  private String valueOrEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
