package ar.edu.uade.pfi.backend.client;

/**
 * Etapa 80: el único punto de unión del que depende {@code ChatService}. Cambiar el modelo/runtime subyacente —
 * Qwen autoalojado hoy, potencialmente {@code OpenAiLlmProvider}/{@code GeminiLlmProvider}/
 * {@code AnthropicLlmProvider} más adelante — implica agregar una nueva implementación de esta interfaz y
 * cambiar {@code pfi.llm.provider}, sin tocar nunca {@code ChatService} ni la lógica de armado de contexto.
 * Ninguna implementación puede llamar a FastAPI, a la base de datos ni al filesystem — un proveedor solo
 * convierte un request de dominio en un request wire y una respuesta wire en un resultado de dominio.
 */
public interface LlmProvider {
    LlmChatResult complete(LlmChatRequest request);

    /**
     * Etapa 81-T: un probe de liveness barato y sin efectos secundarios — nunca genera tokens, nunca
     * lanza excepción. Lo usa únicamente {@code SystemDiagnosticsService} para distinguir
     * enabled+reachable/enabled+unreachable en {@code /api/system/diagnostics}, de modo que un operador
     * pueda distinguir "LLM deshabilitado" de "LLM habilitado pero el runtime está caído" sin esperar a
     * que falle primero un request de chat real.
     */
    boolean checkAvailability();
}
