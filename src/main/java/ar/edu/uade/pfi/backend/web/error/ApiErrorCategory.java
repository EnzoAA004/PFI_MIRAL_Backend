package ar.edu.uade.pfi.backend.web.error;

/** Clasificación estable y de baja cardinalidad para cada error que la API puede devolver. */
public enum ApiErrorCategory {
  AUTHENTICATION,
  AUTHORIZATION,
  VALIDATION,
  RESOURCE,
  DATABASE,
  AI_UPSTREAM,
  AI_CONTRACT,
  SECURITY,
  INTERNAL
}
