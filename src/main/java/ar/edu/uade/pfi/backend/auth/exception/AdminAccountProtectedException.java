package ar.edu.uade.pfi.backend.auth.exception;

import org.springframework.http.HttpStatus;

/**
 * Se lanza cuando el flujo de activación/aprobación de profesionales apunta a una cuenta que ya lleva
 * ADMIN — ese flujo solo puede tocar cuentas DOCTOR/REVIEWER. Las cuentas de administrador se
 * gestionan mediante un flujo separado y dedicado (no implementado acá).
 */
public class AdminAccountProtectedException extends RuntimeException {
  public AdminAccountProtectedException() {
    super("Las cuentas administrativas no pueden modificarse mediante el flujo de profesionales.");
  }

  public HttpStatus status() {
    return HttpStatus.CONFLICT;
  }

  public String code() {
    return "ADMIN_ACCOUNT_PROTECTED";
  }
}
