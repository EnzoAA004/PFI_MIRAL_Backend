package ar.edu.uade.pfi.backend.dto;

public record StudyUploadSeriesDto(
    /**
     * Identificador de la serie almacenada, para que el lector pueda mostrarla.
     *
     * <p>Ahora se conserva cada serie del estudio, no solo las dos sobre las que corren los modelos. Un estudio lumbar
     * trae T1 y T2 sagitales, T1 y T2 axiales, a veces coronales y un localizador; la IA lee
     * dos de ellas y el médico las lee todas. Esto es lo que le permite al viewer pedir los slices
     * de las otras cinco.
     */
    String inputId,
    String plane,
    String description,
    String weighting,
    Integer sliceCount,
    /** Series cuyos slices no comparten un plano: un localizador. No es un volumen. */
    boolean multiplanar,
    /** Una captura de consola o un reformateo en lugar de datos de imagen adquiridos. */
    boolean derived,
    /**
     * Si esta serie podría ser el input de una corrida de inferencia.
     *
     * <p>False cubre tres motivos distintos que se ven igual en pantalla y no lo son: no hay un
     * modelo para su ponderación (T1 axial), no es un volumen de un solo plano (un localizador), o
     * no son datos adquiridos (una captura de consola).
     */
    boolean analyzable) {}
