# Cloud Environment Variables

| Variable | Requerida | Default local | Ejemplo cloud | Descripcion |
| --- | --- | --- | --- | --- |
| `PORT` | No | `8080` | `8080` o valor inyectado por la plataforma | Puerto HTTP del backend Spring Boot. |
| `PFI_AI_SERVICE_URL` | Si | `http://localhost:8000` | `https://pfi-ai-module-7cxg46zwea-uc.a.run.app` | URL base del AI Module FastAPI. |
| `PFI_AI_AUTH_MODE` | No | `none` | `google-id-token` | Modo de auth Backend -> AI. `none` no intenta ADC; `google-id-token` genera un Google ID Token con ADC/service identity. |
| `PFI_AI_SERVICE_AUDIENCE` | Si con `google-id-token` | vacio | `https://pfi-ai-module-7cxg46zwea-uc.a.run.app` | Audience del ID token. Si queda vacio se deriva de `PFI_AI_SERVICE_URL` como origen HTTP(S), sin trailing slash ni path; si no puede derivarse, falla cerrado. |
| `PFI_AI_TIMEOUT_SECONDS` | No | `60` | `60` | Timeout para llamadas HTTP al AI Module. |
| `PFI_CORS_ALLOWED_ORIGINS` | Si para frontend cloud | `http://localhost:5173,http://localhost:3000` | `https://frontend.example.com` | Origenes permitidos para el frontend React. Separar varios con coma. |
| `SPRING_PROFILES_ACTIVE` | Si en produccion | sin perfil | `production` | Activa los validadores fail-closed de produccion. |
| `PFI_PERSISTENCE_MODE` | Si en produccion | `memory` | `postgres` | En produccion debe ser `postgres`; el backend rechaza arrancar con persistencia efimera. |
| `DATABASE_URL` / `PFI_DATABASE_URL` | Si con postgres | vacio | `postgresql://...` o `jdbc:postgresql://...` | Conexion PostgreSQL usada por persistencia domain/auth. No commitear credenciales reales. |
| `PFI_AUTH_JWT_SECRET` | Si en produccion | demo local | secreto >= 32 bytes | Clave HS256 para JWT; no usar el default del repo en produccion. |

## Ejemplo Render/Railway

```text
PFI_AI_SERVICE_URL=https://pfi-ai-module.example.com
PFI_AI_AUTH_MODE=google-id-token
PFI_AI_SERVICE_AUDIENCE=https://pfi-ai-module.example.com
PFI_AI_TIMEOUT_SECONDS=60
PFI_CORS_ALLOWED_ORIGINS=https://pfi-frontend.example.com
SPRING_PROFILES_ACTIVE=production
PFI_PERSISTENCE_MODE=postgres
DATABASE_URL=postgresql://user:password@host:5432/db
PFI_AUTH_JWT_SECRET=<secret-random-32-bytes-min>
```

`PORT` normalmente lo define la plataforma. Si se configura manualmente, debe coincidir con el puerto expuesto por el servicio.

## AI Module privado en Cloud Run

Local sin Google Cloud:

```text
PFI_AI_SERVICE_URL=http://localhost:8000
PFI_AI_AUTH_MODE=none
```

Cloud Run privado:

```text
PFI_AI_SERVICE_URL=https://pfi-ai-module-7cxg46zwea-uc.a.run.app
PFI_AI_AUTH_MODE=google-id-token
PFI_AI_SERVICE_AUDIENCE=https://pfi-ai-module-7cxg46zwea-uc.a.run.app
```

Con `google-id-token`, el backend usa Application Default Credentials. En Cloud Run esas credenciales deben venir de la service identity del backend, sin JSON de service account, sin `GOOGLE_APPLICATION_CREDENTIALS` y sin reenviar el JWT del usuario al AI Module.
