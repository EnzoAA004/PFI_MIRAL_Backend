package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiPlaneMeasurementV2Dto(
    String id,
    String labelKey,
    Double value,
    String unit,
    Double confidence,
    String source,
    String status,
    String plane,
    String level,
    /**
     * Qué describe la medición: un nivel vertebral o el estudio como un todo.
     *
     * <p>Separa "no se pudo asignar un nivel" de "no aplica ningún nivel". Sin esto ambos llegan como
     * un nivel null y el panel de lectura los archiva bajo el mismo encabezado, lo que en el segundo
     * caso le echa la culpa a la IA por una falla que no tuvo: el área del canal no tiene nivel porque la
     * máscara recorre todo el largo de la columna.
     */
    String levelScope,
    /**
     * Los dos extremos entre los que se tomó la medición, en el coordinateSpace del plano.
     *
     * <p>Es lo que le da un lugar al número. Sin esto un revisor ve "37.37 mm" y no tiene forma
     * de saber desde dónde hasta dónde, así que no puede ni verificar la medición ni corregirla. Vacío
     * cuando la magnitud no es una distancia.
     */
    List<java.util.Map<String, Object>> points,
    /**
     * Derivada de la geometría de otras estructuras y no de una máscara propia.
     *
     * <p>El ángulo segmentario y la listesis salen de los ejes de dos cuerpos vertebrales vecinos.
     * Son las dos mediciones que lleva un informe de columna para las que el modelo no fue entrenado
     * para producir, por lo que viajan marcadas: el viewer las muestra en su propia capa y la decisión de
     * usarlas queda en manos del lector.
     */
    Boolean experimental,
    /** Segunda magnitud cuando la medición la tiene, como el grado de Meyerding. */
    String detail,
    /** Slice en el que se tomó la medición; sin esto un valor no puede ubicarse en la serie. */
    Integer sliceIndex,
    String measurementBasis,
    List<String> linkedLandmarkIds) {}
