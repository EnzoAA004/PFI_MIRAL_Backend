package ar.edu.uade.pfi.backend.service;

/**
 * Catálogo estable de acciones de auditoría para operaciones nuevas/críticas. Los strings literales históricos de acciones ya existentes
 * (p. ej. {@code "asset.snapshot.stored"}, {@code "auth.login.completed"}, {@code
 * "access.denied"}) intencionalmente NO se migran en bloque — P10-B no exige romper
 * la compatibilidad con nada que ya consuma esos strings. Las operaciones críticas nuevas, y cualquier
 * sitio de llamada que se toque de aquí en adelante, deberían usar este catálogo vía {@link #name()} en lugar de inventar
 * otro literal ad hoc.
 */
public enum AuditAction {
  AUTH_LOGIN_SUCCEEDED,
  AUTH_LOGIN_FAILED,
  AUTH_REFRESH_SUCCEEDED,
  AUTH_REFRESH_FAILED,
  PROFESSIONAL_ACTIVATED,
  PROFESSIONAL_DEACTIVATED,
  PASSWORD_CHANGED,
  PROFILE_CHANGE_REQUESTED,
  PROFILE_CHANGE_AUTHORIZED,
  PROFILE_CHANGE_SUBMITTED,
  PROFILE_CHANGE_APPROVED,
  PROFILE_CHANGE_REJECTED,
  ADMIN_BOOTSTRAP_CREATED,
  AI_INPUT_UPLOADED,
  AI_RUN_REQUESTED,
  AI_RUN_COMPLETED,
  AI_RUN_FAILED,
  REVIEW_SAVED,
  REVIEW_UPDATED,
  ASSET_REQUESTED,
  ACCESS_DENIED,
  SECURITY_STATE_UNAVAILABLE
}
