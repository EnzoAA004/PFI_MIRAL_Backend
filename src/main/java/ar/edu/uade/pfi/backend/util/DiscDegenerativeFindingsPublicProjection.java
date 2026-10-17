package ar.edu.uade.pfi.backend.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public projection of a P10.7 disc-degenerative-findings envelope.
 *
 * <p>The frozen checkpoint's raw prediction carries {@code classification.probabilities} per
 * finding: the full distribution over that task's labels. That vector is needed internally --
 * upstream contract validation ({@code DiscDegenerativeFindingsPersistenceService}), traceability,
 * and audit -- and it stays in what gets persisted, but it does not travel to a Frontend consumer:
 * a distribution invites reading the runner-up class as a second opinion, which is not what a
 * single-label report plus mandatory human review means.
 *
 * <p>What does travel is {@code classification.confidence}: the probability of the label that is
 * actually being reported. The reader survey asked for it explicitly -- a categorical finding with
 * no idea of how sure the model is cannot be weighed against the image -- and the rest of the
 * product already reports it this way, next to every measurement. So the projection reduces the
 * vector to its reported component instead of dropping it.
 *
 * <p>The comparison view added a second, strictly additive field: {@code
 * classification.classProbabilities}, the same per-class values in a differently named key.
 * A professional comparing two studies has to be able to look at how each side's result was
 * distributed before reading a change of class as a change at all (a flip between two classes of
 * 0.48 and 0.47 is not the same thing as one between 0.96 and 0.02). It is deliberately NOT named
 * {@code probabilities}: consumers that validate the old contract still reject that key, and the
 * confidence of the reported label keeps travelling exactly as before. The values are the model's
 * own and are never reinterpreted, thresholded or rescaled here; a distribution that is not made
 * of finite numbers in [0, 1] is omitted rather than repaired.
 *
 * <p>Used by both the live {@code POST /api/ai/v2/product/disc-degenerative-findings} response and
 * the persisted {@code GET /api/studies/{caseId}/runs} snapshot, so the two paths cannot silently
 * diverge on what they publish.
 */
public final class DiscDegenerativeFindingsPublicProjection {
  private DiscDegenerativeFindingsPublicProjection() {}

  /**
   * Returns a deep copy of {@code discDegenerativeFindings} where every {@code
   * classification.probabilities} vector is replaced by {@code classification.confidence}, the
   * probability of the reported label. Never mutates {@code source} -- the persisted/internal
   * object keeps its probabilities intact for validation, traceability, and audit.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> sanitize(Map<String, Object> source) {
    if (source == null) return null;
    Map<String, Object> copy = new LinkedHashMap<>(source);
    Object findingsRaw = copy.get("findings");
    if (findingsRaw instanceof List<?> findings) {
      List<Object> sanitizedFindings = new ArrayList<>();
      for (Object item : findings) {
        sanitizedFindings.add(
            item instanceof Map<?, ?> finding
                ? sanitizeFinding((Map<String, Object>) finding)
                : item);
      }
      copy.put("findings", sanitizedFindings);
    }
    return copy;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> sanitizeFinding(Map<String, Object> finding) {
    Map<String, Object> copy = new LinkedHashMap<>(finding);
    Object classificationRaw = copy.get("classification");
    if (classificationRaw instanceof Map<?, ?> classification) {
      Map<String, Object> sanitizedClassification =
          new LinkedHashMap<>((Map<String, Object>) classification);
      Object probabilities = sanitizedClassification.remove("probabilities");
      Double confidence = confidenceForLabel(probabilities, sanitizedClassification.get("label"));
      if (confidence != null) sanitizedClassification.put("confidence", confidence);
      Map<String, Double> distribution = distributionOf(probabilities);
      if (distribution != null) sanitizedClassification.put("classProbabilities", distribution);
      copy.put("classification", sanitizedClassification);
    }
    return copy;
  }

  /**
   * The model's per-class values as published, or {@code null} when any of them is not a finite
   * number in [0, 1] (or there are none). All-or-nothing: a partly repaired distribution would
   * read as a measurement the model never made.
   */
  private static Map<String, Double> distributionOf(Object probabilities) {
    if (!(probabilities instanceof Map<?, ?> distribution) || distribution.isEmpty()) return null;
    Map<String, Double> published = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : distribution.entrySet()) {
      if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof Number number)) {
        return null;
      }
      double value = number.doubleValue();
      if (!Double.isFinite(value) || value < 0 || value > 1) return null;
      published.put(name, value);
    }
    return published;
  }

  /**
   * The reported label's own probability, or {@code null} when the prediction does not carry one
   * for it. Null over a fallback on purpose: a finding without a confidence is shown as "sin
   * confianza informada", while a zero or a made-up default would be read as a real measurement of
   * how sure the model was.
   */
  private static Double confidenceForLabel(Object probabilities, Object label) {
    if (!(probabilities instanceof Map<?, ?> distribution) || !(label instanceof String name)) {
      return null;
    }
    Object value = distribution.get(name);
    if (!(value instanceof Number number)) return null;
    double confidence = number.doubleValue();
    if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) return null;
    return confidence;
  }
}
