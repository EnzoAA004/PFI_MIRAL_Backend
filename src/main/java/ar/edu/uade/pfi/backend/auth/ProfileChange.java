package ar.edu.uade.pfi.backend.auth;

import java.time.Instant;

/**
 * Estado de una solicitud de cambio de datos del perfil profesional (nombre, matrícula, institución).
 *
 * <p>Ciclo: REQUESTED (el profesional pidió el cambio) -> AUTHORIZED (un administrador lo autorizó y el
 * profesional recibió un código por correo) -> UNLOCKED (ingresó el código; puede editar) ->
 * PENDING_REVIEW (guardó los cambios; la cuenta queda en solo lectura hasta que un administrador los
 * revise). Mientras no hay solicitud, no existe fila.
 */
public record ProfileChange(
    String email,
    Status status,
    String note,
    String tokenHash,
    Instant expiresAt,
    String previousFullName,
    String previousLicenseNumber,
    String previousInstitution,
    Instant requestedAt,
    Instant updatedAt) {

  public enum Status {
    REQUESTED,
    AUTHORIZED,
    UNLOCKED,
    PENDING_REVIEW
  }

  public ProfileChange withStatus(Status newStatus, String newTokenHash, Instant newExpiresAt) {
    return new ProfileChange(
        email,
        newStatus,
        note,
        newTokenHash,
        newExpiresAt,
        previousFullName,
        previousLicenseNumber,
        previousInstitution,
        requestedAt,
        Instant.now());
  }
}
