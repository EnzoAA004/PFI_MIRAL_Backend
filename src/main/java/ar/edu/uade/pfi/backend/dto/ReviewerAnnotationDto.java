package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Forma wire de una anotación del revisor.
 *
 * <p>{@code id} y {@code createdAt} se devuelven tal cual en lugar de ser asignados por el servidor: el
 * cliente del revisor crea las anotaciones localmente mientras lee y solo persiste cuando guarda, así que
 * la identidad tiene que sobrevivir a ese ida y vuelta para que el cliente concilie lo que ya tiene en
 * pantalla.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewerAnnotationDto(
    String id,
    String scope,
    String kind,
    /** Qué herramienta la tomó, cuando kind es "measurement". Sin esto la figura se pierde. */
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
    String createdAt) {}
