package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.dto.ChatContextInfoDto;
import ar.edu.uade.pfi.backend.dto.ChatConversationDetailDto;
import ar.edu.uade.pfi.backend.dto.ChatConversationHistoryResponseDto;
import ar.edu.uade.pfi.backend.dto.ChatRequestDto;
import ar.edu.uade.pfi.backend.dto.ChatResponseDto;
import ar.edu.uade.pfi.backend.service.ChatService;
import ar.edu.uade.pfi.backend.web.filter.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asistente sobre los resultados reales de un estudio o del par A/B del visor dual. Exige el rol profesional en todos los
 * endpoints: entrega valores de mediciones y hallazgos, y consulta al modelo con ellos. Sin autenticacion responde 401 (filtro
 * de autenticacion) y sin rol profesional, 403.
 */
@RestController
@RequestMapping("/api/studies/{caseId}")
public class ChatController {
    private final ChatService chatService;
    private final RoleAuthorizationService authorizationService;

    public ChatController(ChatService chatService, RoleAuthorizationService authorizationService) {
        this.chatService = chatService;
        this.authorizationService = authorizationService;
    }

    @PostMapping("/chat")
    public ChatResponseDto chat(@PathVariable String caseId, @RequestBody(required = false) ChatRequestDto request, HttpServletRequest httpRequest) {
        authorizationService.requireProfessional(httpRequest, caseId);
        ChatRequestDto body = request == null ? new ChatRequestDto(null, null, null, null, null) : request;
        return chatService.chat(caseId, body, traceId(httpRequest));
    }

    @GetMapping("/chat-context")
    public ChatContextInfoDto context(
        @PathVariable String caseId,
        @RequestParam(required = false) String runId,
        @RequestParam(required = false) String referenceCaseId,
        @RequestParam(required = false) String referenceRunId,
        HttpServletRequest httpRequest
    ) {
        authorizationService.requireProfessional(httpRequest, caseId);
        return chatService.context(caseId, runId, referenceCaseId, referenceRunId);
    }

    @GetMapping("/chat-conversations")
    public ChatConversationHistoryResponseDto history(
        @PathVariable String caseId,
        @RequestParam(required = false) String runId,
        @RequestParam(required = false) String referenceRunId,
        HttpServletRequest httpRequest
    ) {
        authorizationService.requireProfessional(httpRequest, caseId);
        return chatService.history(caseId, runId, referenceRunId);
    }

    @GetMapping("/chat-conversations/{conversationId}")
    public ChatConversationDetailDto detail(@PathVariable String caseId, @PathVariable String conversationId, HttpServletRequest httpRequest) {
        authorizationService.requireProfessional(httpRequest, caseId);
        return chatService.detail(caseId, conversationId);
    }

    private String traceId(HttpServletRequest request) {
        Object attribute = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (attribute instanceof String value && !value.isBlank()) return value;
        String header = request.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        return header == null || header.isBlank() ? null : header;
    }
}
