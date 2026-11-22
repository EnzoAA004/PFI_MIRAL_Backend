package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MultiplanarRunRequestDto(
    @NotBlank String caseId,
    String sagittalInputId,
    String axialInputId,
    String sagittalInputPath,
    String axialInputPath,
    String sagittalModelKey,
    String axialModelKey,
    Boolean allowContractFallback,
    /**
     * Todas las series que traía el estudio subido, para que el lector pueda mostrarlas.
     *
     * <p>Opcional, y vacío para una corrida armada a partir de dos uploads por plano en lugar de un archivo
     * de estudio. Los dos planos analizados siguen viajando en {@code sagittalInputId}/{@code
     * axialInputId}: esta lista no los reemplaza, agrega los que ningún modelo procesa — los T1,
     * el T1 axial sin modelo, el localizador — que el médico necesita y que antes se
     * descartaban en la ingesta.
     */
    @Valid List<StudyUploadSeriesDto> studySeries,
    Map<String, Object> metadata) {
  /**
   * Una corrida sin un catálogo de estudio detrás: dos uploads por plano.
   *
   * <p>Se mantiene como constructor propio en lugar de obligar a cada llamante a pasar {@code null}: "esta corrida no
   * vino de un archivo de estudio" es un caso real, no una omisión.
   */
  public MultiplanarRunRequestDto(
      String caseId,
      String sagittalInputId,
      String axialInputId,
      String sagittalInputPath,
      String axialInputPath,
      String sagittalModelKey,
      String axialModelKey,
      Boolean allowContractFallback,
      Map<String, Object> metadata) {
    this(
        caseId,
        sagittalInputId,
        axialInputId,
        sagittalInputPath,
        axialInputPath,
        sagittalModelKey,
        axialModelKey,
        allowContractFallback,
        List.of(),
        metadata);
  }

  public MultiplanarRunRequestDto {
    if (studySeries == null) studySeries = List.of();
  }
}
