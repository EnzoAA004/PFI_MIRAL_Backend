package ar.edu.uade.pfi.backend.dto;

import java.util.List;

public record StudyUploadResponseDto(
    String caseId,
    String studyId,
    List<StudyUploadSeriesDto> seriesFound,
    StudyUploadInputDto sagittal,
    StudyUploadInputDto axial,
    /**
     * T1 y T2 sagitales como dos inputs registrados de forma independiente y analizables de forma independiente (P10.7
     * los trata como modalidades separadas, nunca registradas píxel a píxel entre sí). {@code sagittal}
     * más arriba sigue siendo el ganador de un solo plano usado por el contrato legacy {@code /api/ai/multiplanar/run};
     * estos son para las rutas de producto de serie completa P10.9 y de hallazgos degenerativos de disco,
     * que necesitan ambos cuando ambos existen.
     */
    StudyUploadInputDto sagittalT1,
    StudyUploadInputDto sagittalT2,
    List<String> warnings,
    boolean humanReviewRequired,
    boolean notClinicalDiagnosis) {}
