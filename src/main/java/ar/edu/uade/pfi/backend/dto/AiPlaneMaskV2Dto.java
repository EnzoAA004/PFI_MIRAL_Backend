package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiPlaneMaskV2Dto(
    String id,
    String classKey,
    Integer classId,
    /** Nivel lumbar de esta instancia, cuando pudo determinarse. */
    String level,
    /** Color asignado a la instancia para que las estructuras vecinas se mantengan distinguibles. */
    String color,
    Double confidence,
    Boolean enabled,
    Boolean editable,
    String coordinateSpace,
    Map<String, Object> geometry) {}
