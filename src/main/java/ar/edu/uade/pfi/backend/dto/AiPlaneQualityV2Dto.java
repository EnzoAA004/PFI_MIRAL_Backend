package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiPlaneQualityV2Dto(
    Integer maskCount,
    Integer landmarkCount,
    Integer measurementCount,
    Double meanConfidence,
    Double meanForegroundConfidence,
    Double foregroundRatio,
    /**
     * Cuántos slices de la serie tienen un preview persistido. Puede ser menor que la cantidad de
     * slices de la serie: una corrida hecha antes de que existiera el catálogo de previews solo conservó la imagen del
     * slice inferido, y el viewer necesita la diferencia para no prometer un frame que nunca se escribió.
     */
    Integer slicePreviewCount,
    /**
     * Geometría del volumen en el espacio del paciente: origen, dirección, spacing, eje de slices y frame of
     * reference.
     *
     * <p>Se transporta de forma opaca. Es lo que le permitiría a un viewer ubicar el slice de un plano sobre el otro;
     * sin esto una línea de referencia entre sagital y axial sería una coordenada inventada. El
     * frame of reference viaja con ella porque dos estudios tienen geometrías perfectamente bien formadas
     * que no son comparables entre sí.
     */
    java.util.Map<String, Object> volumeGeometry,
    /**
     * Metadatos crudos de píxeles por slice: count, width, height, dtype, byteOrder, min, max. Se transporta
     * de forma opaca — es lo que le permite al viewer aplicar windowing sobre intensidades reales en vez de filtrar el brillo
     * sobre un PNG de 8 bits ya ventaneado.
     */
    java.util.Map<String, Object> slicePixels,
    /**
     * Extensión craneocaudal de cada espacio discal: {@code [{level, worldTop, worldBottom}]}.
     *
     * <p>Se transporta de forma opaca, y solo el plano sagital la publica — es el plano donde los
     * espacios discales se ven completos y pueden contarse hacia arriba desde la unión lumbosacra. El módulo de IA
     * la usa para nombrar el nivel del slice axial, cosa que la corrida axial no puede hacer por sí sola: su
     * modelo no segmenta nada contable, así que toda medición axial llegaba antes sin nivel y
     * terminaba bajo "no se pudo asignar".
     */
    List<java.util.Map<String, Object>> discLevels,
    /**
     * Nivel discal de cada corte axial que cae en un espacio discal: {@code [{index, level}]}.
     *
     * <p>Contraparte de {@code discLevels}: solo el plano axial lo publica, y solo cuando la
     * corrida tuvo tambien sagital. Los cortes que no atraviesan ningun disco no aparecen.
     *
     * <p>Es por corte y no uno solo para la serie porque una serie axial lumbar se adquiere en
     * bloques angulados, uno por disco. El visor lo necesita para nombrar el corte que el medico
     * esta mirando y para no pedir una clasificacion subarticular bajo un nivel que no es. Viaja
     * opaco: el backend no lo interpreta.
     */
    List<java.util.Map<String, Object>> sliceLevels,
    List<String> warnings) {}
