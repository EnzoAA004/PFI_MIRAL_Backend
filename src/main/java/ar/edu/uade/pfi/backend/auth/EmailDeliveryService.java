package ar.edu.uade.pfi.backend.auth;

import java.time.Duration;

/**
 * Entrega códigos de verificación de un solo uso a la casilla de un profesional. Cuando no hay una implementación
 * configurada ({@code pfi.auth.email-delivery=none}, el valor por defecto) {@link AuthService} mantiene el
 * comportamiento histórico "email-demo": no se envía nada y el código nunca se devuelve a los clientes.
 */
public interface EmailDeliveryService {

  /**
   * @param toEmail destinatario ya normalizado (el email de la cuenta)
   * @param purpose {@code REGISTER} o {@code LOGIN}
   * @param code código de seis dígitos; nunca debe loguearse
   * @throws EmailDeliveryException si el mensaje no pudo entregarse al servidor de correo
   */
  void sendVerificationCode(String toEmail, String purpose, String code, Duration validFor);

  /** Código de un solo uso para habilitar la edición del perfil (lo pidió el profesional y lo autorizó un administrador). */
  default void sendProfileChangeToken(String toEmail, String token, Duration validFor) {}

  /** Le cuenta al profesional por qué un administrador no aprobó su registro. */
  default void sendRegistrationRejected(String toEmail, String fullName, String reason) {}

  /** Avisa a soporte que un profesional pidió cambiar los datos de su perfil. */
  default void notifyProfileChangeRequested(String doctorName, String doctorEmail, String note) {}

  /** Se lanza sin el código ni ninguna credencial en su mensaje. */
  class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
