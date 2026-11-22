package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MultiplanarRunApiRequestDto(
    @NotBlank String caseId,
    StudyMetadataDto studyMetadata,
    String sagittalInputId,
    String axialInputId,
    String sagittalInputPath,
    String axialInputPath,
    String sagittalModelKey,
    String axialModelKey,
    Boolean allowContractFallback,
    /**
     * Todas las series que traía el estudio subido, tal como las devuelve el upload del estudio.
     *
     * <p>Opcional: una corrida armada a partir de dos uploads por plano no tiene un estudio detrás y no envía nada
     * acá. Cuando está presente es lo que le permite a la sala de lectura mostrar las series sobre las que no corrió ningún modelo.
     */
    @Valid List<StudyUploadSeriesDto> studySeries,
    Map<String, Object> metadata) {}
