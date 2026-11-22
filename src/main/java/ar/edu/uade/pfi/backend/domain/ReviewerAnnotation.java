package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Una anotación propia del revisor sobre una corrida: una medición que tomó, un marcador que colocó, o una nota
 * que escribió.
 *
 * <p>Distinta de {@link MeasurementCorrection}, que siempre es un valor de la IA que el revisor cambió
 * y por lo tanto siempre tiene un measurementId más un par antes/después. Una anotación no tiene ninguno de los dos — es
 * geometría y texto que produjo el revisor, y puede no corresponder a ninguna medición que
 * haya emitido el modelo.
 *
 * <p>{@code scope} decide dónde se dibuja la anotación, por lo que los invariantes que la hacen ubicable
 * se aplican acá además de en los CHECK constraints de la tabla: una anotación de alcance slice sin
 * un plano y un índice de slice no puede ubicarse en ninguna imagen, y una de alcance nivel sin un nivel
 * no puede archivarse bajo ningún nivel vertebral.
 *
 * <p>{@code points} están en la base normalizada 0..256 compartida con las máscaras y los landmarks — un
 * espacio relativo al marco, no píxeles PNG, de modo que la geometría se mantiene alineada sin importar la resolución
 * a la que se renderice el slice.
 */
public record ReviewerAnnotation(
    String id,
    String studyRunId,
    String scope,
    String kind,
    /**
     * Qué herramienta tomó la medición, cuando {@code kind} es "measurement".
     *
     * <p>Separado de {@code kind} porque responden preguntas distintas: kind dice si esto
     * es una medición en absoluto, esto dice cómo se midió. Sin esto la figura se pierde al
     * recargar — un ángulo son cuatro puntos y una listesis son tres, así que un viewer que no conoce el
     * tipo los redibuja como una distancia entre los dos primeros, que es una medición que nadie tomó.
     */
    String measurementKind,
    String plane,
    String seriesId,
    Integer sliceIndex,
    String level,
    List<Map<String, Object>> points,
    Double value,
    String unit,
    String text,
    String author,
    Instant createdAt) {
  private static final List<String> SCOPES = List.of("study", "level", "slice");
  private static final List<String> KINDS = List.of("measurement", "marker", "note");
  private static final List<String> PLANES = List.of("sagittal", "axial");
  private static final List<String> UNITS = List.of("mm", "px");
  private static final List<String> MEASUREMENT_KINDS =
      List.of("distance", "angle", "listhesis", "roi", "probe");

  public ReviewerAnnotation {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(studyRunId, "studyRunId");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(createdAt, "createdAt");
    require(SCOPES.contains(scope), "scope invalido: " + scope);
    require(
        measurementKind == null || MEASUREMENT_KINDS.contains(measurementKind),
        "measurementKind invalido: " + measurementKind);
    require(KINDS.contains(kind), "kind invalido: " + kind);
    require(plane == null || PLANES.contains(plane), "plane invalido: " + plane);
    require(unit == null || UNITS.contains(unit), "unit invalida: " + unit);
    require(
        !"slice".equals(scope) || (plane != null && sliceIndex != null),
        "una anotacion de corte necesita plano e indice de corte");
    require(!"level".equals(scope) || level != null, "una anotacion de nivel necesita el nivel");
    require(sliceIndex == null || sliceIndex >= 0, "sliceIndex no puede ser negativo");
    points = points == null ? List.of() : List.copyOf(points);
    text = text == null ? "" : text;
    author = author == null ? "" : author;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }
}
