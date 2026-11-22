package ar.edu.uade.pfi.backend.auth.exception;

import org.springframework.http.HttpStatus;

/** Se lanza cuando una operación dejaría al sistema sin ninguna cuenta ADMIN que no sea demo. */
public class LastAdminProtectionException extends RuntimeException {
  public LastAdminProtectionException() {
    super("La operacion dejaria al sistema sin un administrador activo.");
  }

  public HttpStatus status() {
    return HttpStatus.CONFLICT;
  }

  public String code() {
    return "LAST_ADMIN_PROTECTION";
  }
}
