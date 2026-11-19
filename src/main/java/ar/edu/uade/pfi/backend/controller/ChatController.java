package ar.edu.uade.pfi.backend.controller;
import ar.edu.uade.pfi.backend.web.filter.TraceIdFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;

import ar.edu.uade.pfi.backend.dto.ChatConversationDetailDto;
import ar.edu.uade.pfi.backend.dto.ChatConversationHistoryResponseDto;
import ar.edu.uade.pfi.backend.dto.ChatRequestDto;
import ar.edu.uade.pfi.backend.dto.ChatResponseDto;
import ar.edu.uade.pfi.backend.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Stage 80. Same {@code /api/studies/{caseId}/...} sub-resource convention as every other
 * controller in this family. Read endpoints are ungated (same convention as stage 76/79's GET
 * history/detail) — chat itself carries no elevated privilege beyond viewing the Study.
 */
@RestController
@RequestMapping("/api/studies/{caseId}")
public class ChatController {
    private final ChatService chatService;
    private final RoleAuthorizationService authorizationService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
        this.authorizationService = null;
    }

    @Autowired
    public ChatController(ChatService chatService, RoleAuthorizationService authorizationService) {
        this.chatService = chatService;
        this.authorizationService = authorizationService;
    }

    @PostMapping("/chat")
    public ChatResponseDto chat(@PathVariable String caseId, @RequestBody(required = false) ChatRequestDto request, HttpServletRequest httpRequest) {
        if (authorizationService != null) authorizationService.requireStudyWrite(httpRequest, caseId);
        ChatRequestDto body = request == null ? new ChatRequestDto(null, null, null, null) : request;
        if (authorizationService != null && body.baselineCaseId() != null && !body.baselineCaseId().isBlank()) {
            authorizationService.requireStudyRead(httpRequest, body.baselineCaseId());
        }
        return chatService.chat(caseId, body, traceId(httpRequest));
    }

    @GetMapping("/chat-conversations")
    public ChatConversationHistoryResponseDto history(@PathVariable String caseId, HttpServletRequest httpRequest) {
        if (authorizationService != null) authorizationService.requireStudyRead(httpRequest, caseId);
        return chatService.history(caseId);
    }

    ChatConversationHistoryResponseDto history(String caseId) { return chatService.history(caseId); }

    @GetMapping("/chat-conversations/{conversationId}")
    public ChatConversationDetailDto detail(@PathVariable String caseId, @PathVariable String conversationId, HttpServletRequest httpRequest) {
        if (authorizationService != null) authorizationService.requireStudyRead(httpRequest, caseId);
        return chatService.detail(caseId, conversationId);
    }

    ChatConversationDetailDto detail(String caseId, String conversationId) { return chatService.detail(caseId, conversationId); }

    private String traceId(HttpServletRequest request) {
        Object attribute = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (attribute instanceof String value && !value.isBlank()) return value;
        String header = request.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        return header == null || header.isBlank() ? null : header;
    }
}
