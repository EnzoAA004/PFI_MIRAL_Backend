package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Body del request público de {@code POST /api/studies/{caseId}/product-analysis}. {@code caseId}
 * viene del path, no del body — este es un subrecurso de un estudio, siguiendo la convención
 * {@code /api/studies/{caseId}/...} ya usada en el resto de esta familia de controllers.
 *
 * <p>Etapa 76: {@code sagittalInputId}/{@code axialInputId} están deprecados y se ignoran. El
 * Backend ahora resuelve ambos a partir de los inputs registrados del propio Study vía
 * {@code AiStudyInputResolver} — el Frontend solo necesita enviar {@code caseId} (path) y
 * {@code metadata} opcional. Los campos se mantienen en el wire, en lugar de eliminarse del todo, solo
 * para que un llamante existente que todavía los envía no falle en la deserialización; no tienen efecto.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiProductAnalysisApiRequestDto(
    @Deprecated(forRemoval = true) String sagittalInputId,
    @Deprecated(forRemoval = true) String axialInputId,
    Map<String, Object> metadata
) {
}
