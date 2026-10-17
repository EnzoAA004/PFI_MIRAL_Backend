# Inicia el proveedor LLM local del asistente (LM Studio, API compatible con OpenAI) SOLO en 127.0.0.1.
# Primera vez: descargar e importar el modelo (ver docs/ASISTENTE_LLM_LOCAL.md).
param(
  [string]$Model = "qwen3-4b-instruct-2507",
  [int]$Port = 1234,
  [int]$Context = 8192
)
lms server start --port $Port --bind 127.0.0.1
lms load $Model --identifier $Model -c $Context --gpu max --parallel 1 -y
lms ps
