package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.Objects;

public record InputResource(
    String id,
    String studyId,
    String plane,
    String inputId,
    String format,
    long size,
    /** Descripción de la serie tal como la declaró el estudio: {@code t2_tse_sag_384}. */
    String description,
    /** {@code t1}, {@code t2} o {@code unknown}. */
    String weighting,
    int sliceCount,
    /** Series cuyos slices no comparten un plano: un localizador. No es un volumen. */
    boolean multiplanar,
    /** Una captura de consola o un reformateo en lugar de datos de imagen adquiridos. */
    boolean derived,
    /**
     * Si esta serie podría ser el input de una corrida de inferencia.
     *
     * <p>False cubre tres motivos que se ven igual en pantalla y no lo son: no hay un modelo para
     * su ponderación (T1 axial), no es un volumen de un solo plano (un localizador), o no son
     * datos adquiridos (una captura de consola).
     */
    boolean analyzable,
    Instant createdAt) {
  /** Forma previa a BE-015: los dos planos analizados, antes de que el estudio conservara todas las series. */
  public InputResource(
      String id,
      String studyId,
      String plane,
      String inputId,
      String format,
      long size,
      Instant createdAt) {
    this(id, studyId, plane, inputId, format, size, "", "", 0, false, false, true, createdAt);
  }

  public InputResource {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(studyId, "studyId");
    Objects.requireNonNull(plane, "plane");
    Objects.requireNonNull(inputId, "inputId");
    Objects.requireNonNull(format, "format");
    Objects.requireNonNull(createdAt, "createdAt");
    if (description == null) description = "";
    if (weighting == null) weighting = "";
    if (size < 0) throw new IllegalArgumentException("size must be non-negative");
    if (sliceCount < 0) throw new IllegalArgumentException("sliceCount must be non-negative");
  }
}
