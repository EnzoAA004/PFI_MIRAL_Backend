package ar.edu.uade.pfi.backend.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proyección pública de un envelope de hallazgos degenerativos de disco P10.7.
 *
 * <p>La predicción cruda del checkpoint congelado lleva {@code classification.probabilities} por
 * hallazgo: la distribución completa sobre las etiquetas de esa tarea. Ese vector se necesita internamente --
 * validación del contrato upstream ({@code DiscDegenerativeFindingsPersistenceService}), trazabilidad
 * y auditoría -- y se queda en lo que se persiste, pero no viaja hacia un consumidor Frontend:
 * una distribución invita a leer la segunda clase como una segunda opinión, que no es lo que significa un
 * reporte de etiqueta única más revisión humana obligatoria.
 *
 * <p>Lo que sí viaja es {@code classification.confidence}: la probabilidad de la etiqueta que
 * efectivamente se informa. La encuesta a lectores lo pidió explícitamente -- un hallazgo categórico sin
 * idea de qué tan seguro está el modelo no puede sopesarse contra la imagen -- y el resto del
 * producto ya lo informa así, junto a cada medición. Entonces la proyección reduce el
 * vector a su componente informado en lugar de descartarlo.
 *
 * <p>Lo usan tanto la respuesta en vivo de {@code POST /api/ai/v2/product/disc-degenerative-findings} como
 * el snapshot persistido de {@code GET /api/studies/{caseId}/runs}, para que los dos caminos no puedan divergir en silencio
 * en lo que publican.
 */
public final class DiscDegenerativeFindingsPublicProjection {
  private DiscDegenerativeFindingsPublicProjection() {}

  /**
   * Devuelve una copia profunda de {@code discDegenerativeFindings} donde cada vector {@code
   * classification.probabilities} se reemplaza por {@code classification.confidence}, la
   * probabilidad de la etiqueta informada. Nunca muta {@code source} -- el objeto persistido/interno
   * conserva sus probabilidades intactas para validación, trazabilidad y auditoría.
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
      copy.put("classification", sanitizedClassification);
    }
    return copy;
  }

  /**
   * La probabilidad propia de la etiqueta informada, o {@code null} cuando la predicción no trae una
   * para ella. Null en lugar de un fallback a propósito: un hallazgo sin confianza se muestra como "sin
   * confianza informada", mientras que un cero o un valor por defecto inventado se leería como una medición real de
   * qué tan seguro estaba el modelo.
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
