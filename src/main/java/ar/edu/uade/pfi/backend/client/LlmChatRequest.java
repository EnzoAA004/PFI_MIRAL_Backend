package ar.edu.uade.pfi.backend.client;

import java.util.List;

/**
 * Etapa 80: request de chat agnóstico del proveedor. {@code temperature}/{@code maxOutputTokens} siempre
 * provienen de {@code LlmProperties} (configuración del lado servidor), nunca del usuario final — no hay
 * ningún camino por el cual un request de chat permita a quien llama cambiar los parámetros de generación.
 */
public record LlmChatRequest(List<LlmChatMessage> messages, double temperature, int maxOutputTokens) {
}
