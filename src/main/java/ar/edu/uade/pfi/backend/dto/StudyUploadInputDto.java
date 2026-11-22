package ar.edu.uade.pfi.backend.dto;

public record StudyUploadInputDto(
    String inputId,
    /**
     * Posición en {@code seriesFound} de la serie a partir de la cual se analiza este plano.
     *
     * <p>Una posición y no el SeriesInstanceUID DICOM: ese UID lleva de vuelta al estudio de origen en
     * el PACS de origen, así que no sale de un pipeline desidentificado. El lector igual necesita
     * ver cuál de las series listadas produjo los resultados — emparejar por descripción marcaría
     * la equivocada en un estudio con dos series que la comparten — y un índice dentro de esta misma respuesta
     * lo dice sin llevar nada de vuelta al origen.
     */
    Integer seriesIndex,
    String plane,
    String format,
    long size,
    String description,
    String weighting,
    Integer sliceCount) {}
