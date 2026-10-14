package ar.edu.uade.pfi.backend.domain;

import java.util.Locale;
import java.util.Map;

/**
 * Ponderación de una serie de entrada y de dónde sale ese dato.
 *
 * <p>{@code USER_DECLARED} es lo que dijo quien cargó el archivo; {@code AUTO_DETECTED} es lo que
 * leyó la ingesta de un estudio DICOM (descripción de la serie o tiempo de eco). Ninguna es una
 * validación del modelo, y la descripción de {@code other} es texto libre que nunca se interpreta
 * como ponderación.
 *
 * <p>Todo dato ausente, ilegible o incoherente es {@link #unknown()}: nunca se completa con T2 ni se
 * deduce del nombre del archivo.
 */
public record InputSequence(String value, String source, String description) {
  public static final String T1 = "t1";
  public static final String T2 = "t2";
  public static final String OTHER = "other";
  public static final String UNKNOWN = "unknown";
  public static final String USER_DECLARED = "user_declared";
  public static final String AUTO_DETECTED = "auto_detected";

  public static final int MAX_DESCRIPTION_CHARS = 80;

  private static final InputSequence UNKNOWN_SEQUENCE = new InputSequence(UNKNOWN, UNKNOWN, "");

  public InputSequence {
    value = value == null ? UNKNOWN : value;
    source = source == null ? UNKNOWN : source;
    description = description == null ? "" : description;
  }

  public static InputSequence unknown() {
    return UNKNOWN_SEQUENCE;
  }

  public boolean isKnown() {
    return !UNKNOWN.equals(value);
  }

  /** {@code t1} o {@code t2}: las únicas que pueden coincidir entre dos estudios. */
  public boolean isComparableWeighting() {
    return T1.equals(value) || T2.equals(value);
  }

  public boolean isDeclared() {
    return USER_DECLARED.equals(source);
  }

  /**
   * Lee el objeto {@code sequence} que publica el módulo de IA ({@code value}, {@code source},
   * {@code description}). Cualquier forma que no sea una pareja valor/procedencia válida es
   * desconocida.
   */
  public static InputSequence fromMap(Map<?, ?> raw) {
    if (raw == null) return unknown();
    String value = lower(raw.get("value"));
    String source = lower(raw.get("source"));
    String description = raw.get("description") instanceof String text ? text.trim() : "";
    return normalized(value, source, description);
  }

  /**
   * Lee la fila de una serie registrada. Las filas de antes de existir la procedencia traen solo
   * {@code weighting}: hasta entonces su único origen era la detección del ZIP DICOM, así que un
   * {@code t1}/{@code t2} sin procedencia se lee como detectado. Una fila sin ponderación es
   * desconocida.
   */
  public static InputSequence fromStored(String weighting, String source, String description) {
    String value = lower(weighting);
    String provenance = lower(source);
    if (provenance.isEmpty() && (T1.equals(value) || T2.equals(value))) provenance = AUTO_DETECTED;
    return normalized(value, provenance, description == null ? "" : description.trim());
  }

  private static InputSequence normalized(String value, String source, String description) {
    boolean validSource = USER_DECLARED.equals(source) || AUTO_DETECTED.equals(source);
    if (!validSource) return unknown();
    if (T1.equals(value) || T2.equals(value)) return new InputSequence(value, source, "");
    if (OTHER.equals(value) && USER_DECLARED.equals(source) && !description.isEmpty()
        && description.length() <= MAX_DESCRIPTION_CHARS) {
      return new InputSequence(OTHER, source, description);
    }
    return unknown();
  }

  private static String lower(Object value) {
    return value instanceof String text ? text.trim().toLowerCase(Locale.ROOT) : "";
  }
}
