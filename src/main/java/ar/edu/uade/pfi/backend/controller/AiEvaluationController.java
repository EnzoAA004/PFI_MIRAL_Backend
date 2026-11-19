package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import ar.edu.uade.pfi.backend.util.AiReportEvidence;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;

@RestController
@RequestMapping("/api/ai/evaluation")
public class AiEvaluationController {
  private final AiServiceOperations aiServiceClient;
  private final RoleAuthorizationService authorization;

  public AiEvaluationController(AiServiceOperations aiServiceClient) {
    this.aiServiceClient = aiServiceClient;
    this.authorization = null;
  }

  @Autowired
  public AiEvaluationController(AiServiceOperations aiServiceClient, @Nullable RoleAuthorizationService authorization) {
    this.aiServiceClient = aiServiceClient;
    this.authorization = authorization;
  }

  @GetMapping("/contract")
  public Map<String, Object> contract(HttpServletRequest request) {
    requireAdmin(request, "ai.evaluation.contract");
    return Map.of(
        "status",
        "evaluation_contract_ready",
        "schemaVersion",
        "evaluation-contract-v1",
        "metrics",
        List.of(
            metric("dice", "segmentation", "higher_is_better"),
            metric("iou", "segmentation", "higher_is_better"),
            metric("hausdorff95", "boundary", "lower_is_better"),
            metric("measurement_error", "measurement", "lower_is_better"),
            metric("review_agreement", "professional_review", "higher_is_better")),
        "requiredEvidence",
        List.of(
            "trace_id",
            "run_id",
            "model_artifact_hash",
            "pipeline_schema_hash",
            "professional_review"),
        "readiness",
        safeReadiness(),
        "humanReviewRequired",
        true,
        "notClinicalDiagnosis",
        true);
  }

  @GetMapping("/summary")
  public Map<String, Object> summary(HttpServletRequest request) {
    requireAdmin(request, "ai.evaluation.summary");
    Map<String, Object> reports = safeReports();
    return Map.of(
        "status",
        "evaluation_summary_ready",
        "reportCount",
        reports.getOrDefault("count", 0),
        "hasReports",
        AiReportEvidence.hasReports(reports),
        "latestRunId",
        AiReportEvidence.latestRunId(reports),
        "reports",
        reports,
        "readiness",
        safeReadiness(),
        "humanReviewRequired",
        true,
        "notClinicalDiagnosis",
        true);
  }

  @GetMapping("/evidence")
  public Map<String, Object> evidence(HttpServletRequest request) {
    requireAdmin(request, "ai.evaluation.evidence");
    Map<String, Object> reports = safeReports();
    return Map.of(
        "status", "evaluation_evidence_ready",
        "latestRunId", AiReportEvidence.latestRunId(reports),
        "reportCount", reports.getOrDefault("count", 0),
        "hasReports", AiReportEvidence.hasReports(reports),
        "readiness", safeReadiness(),
        "requiredEvidence",
            List.of(
                "trace_id",
                "run_id",
                "model_artifact_hash",
                "pipeline_schema_hash",
                "professional_review"),
        "humanReviewRequired", true,
        "notClinicalDiagnosis", true);
  }

  private void requireAdmin(HttpServletRequest request, String entityId) {
    if (authorization != null) authorization.requireAdmin(request, entityId);
  }

  private Map<String, Object> metric(String key, String category, String direction) {
    return Map.of("key", key, "category", category, "direction", direction, "required", true);
  }

  private Map<String, Object> safeReadiness() {
    try {
      return aiServiceClient.readiness();
    } catch (RuntimeException ex) {
      return Map.of("status", "unavailable", "message", "AI Module no disponible.");
    }
  }

  private Map<String, Object> safeReports() {
    try {
      return aiServiceClient.getRecentAgentReports(100);
    } catch (RuntimeException ex) {
      return Map.of(
          "status",
          "unavailable",
          "count",
          0,
          "items",
          List.of(),
          "message",
          "Reportes de evaluacion no disponibles.");
    }
  }
}
