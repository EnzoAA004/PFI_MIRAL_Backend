package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Ponderación de la serie que informa el módulo de IA y de dónde sale: {@code user_declared}
 * (declarada por quien cargó el archivo), {@code auto_detected} (leída de un estudio DICOM) o
 * {@code unknown}. No es una validación del modelo.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AiSequenceV2Dto(String value, String source, String description) {}
