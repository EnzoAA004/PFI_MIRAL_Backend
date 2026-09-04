# EXT-09 — Professional Validation Preparation

**Status: `EXT_09_PROFESSIONAL_VALIDATION_PREP_READY`**

No participants were recruited, no session was run, no result is claimed. This document is the
prepared instrument for a future 15–20 minute session with a real professional reviewer — assets
only, per the explicit instruction not to invent participants or outcomes.

Branch: `extensions/ext-09-professional-validation-prep`, worktree
`PFI_MVPTest_Enzo_Backend_ext09`, base `b097078` (EXT-04B5) — documentation only.

## Session script (15–20 min)

Give the participant a synthetic patient/case already seeded in whichever demo environment is
live at session time (EXT-08's stack if ready, otherwise the most recent individual extension's
own E2E fixture) and ask them to complete the checklist below unaided, narrating as they go
("think aloud"). Do not lead — if they get stuck, note it as `assistance` rather than intervening
immediately.

## Task checklist

| # | Task | Success criterion |
|---|---|---|
| 1 | Encontrar al paciente sintético en la lista de trabajo | Lo abre sin ayuda |
| 2 | Ver el historial longitudinal del paciente | Identifica los estudios previos y sus fechas |
| 3 | Abrir el estudio actual | El visor carga la corrida sagital/axial |
| 4 | Ubicar la segmentación | Reconoce las clases (cuerpo, disco, canal, raíz, foramen) |
| 5 | Ver las mediciones automáticas | Encuentra al menos una medición y su nivel |
| 6 | Ver evidencia visual (región marcada) | Entiende que es una sugerencia, no un diagnóstico |
| 7 | Aceptar o corregir una región/medición | Completa una acción de revisión sin ayuda |
| 8 | Ver la comparación antes/después | Interpreta el delta correctamente |
| 9 | Generar el preinforme / PDF | Encuentra el botón y abre el PDF generado |
| 10 | Usar el asistente conversacional | Hace una pregunta dentro de alcance y la respuesta es coherente |
| 11 | Ver concordancia de evidencia (si disponible) | Entiende que compara solo fuentes equiparables |
| 12 | Ver el mapa de activación (si disponible) | No lo confunde con una zona de patología |

## Data captured per task

For each numbered task: `success` (yes/no/partial), `assistance` (none/verbal hint/pointed to
UI element/did it for them), `time` (seconds, approximate), `confusion` (free text — what they
misunderstood, if anything), `comments` (free text — anything they said unprompted).

## Post-session questionnaire (Likert 1–5, 1=muy en desacuerdo, 5=muy de acuerdo)

1. Claridad: la interfaz fue clara sobre qué estaba viendo en cada momento.
2. Utilidad: las herramientas mostradas serían útiles en mi práctica.
3. Confianza: confío en la información mostrada como apoyo, no como decisión final.
4. Revisión: el flujo de aceptar/corregir se sintió natural.
5. Historial: el historial longitudinal fue fácil de interpretar.
6. Comparación: la comparación antes/después fue clara.
7. Informe: el preinforme/PDF generado fue útil y comprensible.
8. Asistente: el asistente conversacional respondió de forma útil y dentro de lo esperable.
9. Explicabilidad: el mapa de activación (si lo vio) fue comprensible y no confuso.
10. Intención de adopción: usaría esta herramienta como apoyo en mi trabajo diario.

## Open-ended questions

- ¿Qué fue lo más confuso de la experiencia?
- ¿Qué le faltaría a la herramienta para que la use de verdad?
- ¿Hubo algún momento en el que no supo qué hacer?
- ¿Cambiaría algo del orden en que se muestra la información?

## Mandatory safety question

**"¿En algún momento interpretó algo de lo mostrado como un diagnóstico autónomo, sin necesidad
de su propia revisión profesional?"** — a "sí" here is a critical finding regardless of any other
score, and must be reported prominently, not averaged away.

## Recruitment and consent (not executed — process only)

- Recruit licensed radiologists or orthopedists with lumbar MRI reading experience.
- Informed consent: session is recorded (screen + audio, with permission) for internal product
  research only, never shared externally, synthetic data only, no real patient data at any point.
- No compensation model is prescribed here — decide per institutional policy before recruiting.

## Limitations

1. No sessions have been run — this is the prepared instrument, not results.
2. The checklist references EXT-08's integrated demo; if EXT-08 is not ready at session time, use
   the most recently validated individual extension's own E2E environment instead, adjusting task
   numbers 11/12 out if those capabilities aren't available in that environment.
3. Sample size, recruitment channel, and scheduling are institutional decisions outside this
   document's scope.
