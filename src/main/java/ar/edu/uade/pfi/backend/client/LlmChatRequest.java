package ar.edu.uade.pfi.backend.client;

import java.util.List;

/**
 * Stage 80: provider-agnostic chat request. {@code temperature}/{@code maxOutputTokens} always
 * come from {@code LlmProperties} (server-side config), never from the end user — there is no
 * path by which a chat request lets a caller change generation parameters.
 */
public record LlmChatRequest(List<LlmChatMessage> messages, double temperature, int maxOutputTokens) {
}
