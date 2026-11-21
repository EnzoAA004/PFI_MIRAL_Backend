package ar.edu.uade.pfi.backend.auth;

import java.time.Duration;

/**
 * Delivers one-time verification codes to a professional's mailbox. When no implementation is
 * configured ({@code pfi.auth.email-delivery=none}, the default) {@link AuthService} keeps the
 * historical "email-demo" behaviour: nothing is sent and the code is never returned to clients.
 */
public interface EmailDeliveryService {

  /**
   * @param toEmail already-normalized recipient (the account email)
   * @param purpose {@code REGISTER} or {@code LOGIN}
   * @param code six-digit code; must never be logged
   * @throws EmailDeliveryException if the message could not be handed to the mail server
   */
  void sendVerificationCode(String toEmail, String purpose, String code, Duration validFor);

  /** Raised without the code or any credential in its message. */
  class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
