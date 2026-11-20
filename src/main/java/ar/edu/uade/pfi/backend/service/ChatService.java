package ar.edu.uade.pfi.backend.service;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;
import ar.edu.uade.pfi.backend.service.exception.RunReviewException;

import ar.edu.uade.pfi.backend.client.LlmChatMessage;
import ar.edu.uade.pfi.backend.client.LlmChatRequest;
import ar.edu.uade.pfi.backend.client.LlmChatResult;
import ar.edu.uade.pfi.backend.client.LlmProvider;
import ar.edu.uade.pfi.backend.config.LlmProperties;
import ar.edu.uade.pfi.backend.domain.ChatConversation;
import ar.edu.uade.pfi.backend.domain.ChatMessage;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.ChatContextResult;
import ar.edu.uade.pfi.backend.dto.ChatConversationDetailDto;
import ar.edu.uade.pfi.backend.dto.ChatConversationHistoryResponseDto;
import ar.edu.uade.pfi.backend.dto.ChatRequestDto;
import ar.edu.uade.pfi.backend.dto.ChatResponseDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Stage 80: orchestrates a chat turn — validate the question, resolve the Study, build context
 * (stage 76-79 data only), call the one configured {@link LlmProvider}, persist the exchange, and
 * return a response whose safety flags are set unconditionally here, never by the provider
 * (80-L).
 *
 * <p>No AI/FastAPI call happens in this class — {@link LlmProvider} only ever talks to the LLM
 * runtime, never to FastAPI, and the context comes entirely from already-persisted rows.
 */
@Service
public class ChatService {
    private static final int MAX_QUESTION_LENGTH = 2000;

    private final LlmProperties properties;
    private final LlmProvider provider;
    private final StudyChatContextBuilder contextBuilder;
    private final StudyRepository repository;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public ChatService(
        LlmProperties properties,
        LlmProvider provider,
        StudyChatContextBuilder contextBuilder,
        StudyRepository repository,
        ObjectMapper objectMapper,
        @Nullable AuditService auditService
    ) {
        this(properties, provider, contextBuilder, repository, objectMapper, auditService, Clock.systemUTC());
    }

    ChatService(
        LlmProperties properties,
        LlmProvider provider,
        StudyChatContextBuilder contextBuilder,
        StudyRepository repository,
        ObjectMapper objectMapper,
        AuditService auditService,
        Clock clock
    ) {
        this.properties = properties;
        this.provider = provider;
        this.contextBuilder = contextBuilder;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    public ChatResponseDto chat(String caseId, ChatRequestDto request, String traceId) {
        String question = validateQuestion(request == null ? null : request.question());
        if (!properties.resolvedEnabled()) {
            throw new RunReviewException(HttpStatus.SERVICE_UNAVAILABLE, "LLM_PROVIDER_DISABLED", "El asistente conversacional no esta habilitado en este entorno.");
        }
        Study study = repository.findStudyByCaseId(caseId).orElseThrow(() -> new StudyNotFoundException(caseId));

        // Stage 81-C: an existing conversationId must belong to this Study -- same cross-Study
        // IDOR convention as every other lookup in this codebase (404, never 403).
        String requestedConversationId = request.conversationId();
        ChatConversation existingConversation = requestedConversationId == null ? null
            : repository.findChatConversationById(requestedConversationId)
                .filter(candidate -> candidate.studyId().equals(study.id()))
                .orElseThrow(() -> conversationNotFound());

        ChatContextResult context = contextBuilder.build(study, request.analysisId(), request.baselineCaseId());
        String contextJson = writeContextJson(context);
        // Resolved (and IDOR/same-patient-checked) as a side effect of context building above --
        // findStudyByCaseId here only recovers the internal UUID id for FK storage, never re-validates.
        String baselineStudyId = request.baselineCaseId() == null
            ? null
            : repository.findStudyByCaseId(request.baselineCaseId()).map(Study::id).orElse(null);

        List<LlmChatMessage> messages = new ArrayList<>();
        messages.add(new LlmChatMessage("system", ChatSystemPrompts.V1));
        if (existingConversation != null) messages.addAll(historyWindow(existingConversation.id()));
        messages.add(new LlmChatMessage("user", "CONTEXTO (JSON):\n" + contextJson));
        messages.add(new LlmChatMessage("user", question));
        LlmChatResult result = provider.complete(new LlmChatRequest(messages, properties.resolvedTemperature(), properties.resolvedMaxOutputTokens()));

        Instant now = clock.instant();
        String conversationId = existingConversation != null ? existingConversation.id() : UUID.randomUUID().toString();
        ChatConversation conversationToCreate = existingConversation != null ? null : new ChatConversation(conversationId, study.id(), now);
        ChatMessage userMessage = new ChatMessage(
            UUID.randomUUID().toString(), conversationId, "USER", question, now, null, null,
            ChatSystemPrompts.CURRENT_VERSION, request.analysisId(), baselineStudyId, context.study() == null ? null : context.study().contextVersion()
        );
        String assistantMessageId = UUID.randomUUID().toString();
        ChatMessage assistantMessage = new ChatMessage(
            assistantMessageId, conversationId, "ASSISTANT", result.content(), now, result.model(), result.provider(),
            ChatSystemPrompts.CURRENT_VERSION, request.analysisId(), baselineStudyId, context.study() == null ? null : context.study().contextVersion()
        );
        // 81-M: both messages (and the conversation row, if new) are written in one transaction --
        // a Stage-80 gap where three independent writes could leave an orphan user-only turn.
        repository.saveChatTurn(conversationToCreate, userMessage, assistantMessage);

        audit("chat.answered", study.id(), traceId, context.contextSources());

        return new ChatResponseDto(
            result.content(),
            conversationId,
            assistantMessageId,
            result.model(),
            result.provider(),
            ChatSystemPrompts.CURRENT_VERSION,
            now,
            context.analysis() == null ? List.of() : context.analysis().warnings(),
            true,
            true,
            context.contextSources(),
            context.study() == null ? null : context.study().contextVersion(),
            "DRAFT_PENDING_REVIEW"
        );
    }

    public ChatConversationHistoryResponseDto history(String caseId) {
        Study study = repository.findStudyByCaseId(caseId).orElseThrow(() -> new StudyNotFoundException(caseId));
        List<ChatConversationHistoryResponseDto.Summary> summaries = repository.findChatConversationsByStudyId(study.id()).stream()
            .map(conversation -> new ChatConversationHistoryResponseDto.Summary(
                conversation.id(), conversation.createdAt(), repository.findChatMessagesByConversationId(conversation.id()).size()
            ))
            .toList();
        return new ChatConversationHistoryResponseDto(caseId, summaries);
    }

    public ChatConversationDetailDto detail(String caseId, String conversationId) {
        Study study = repository.findStudyByCaseId(caseId).orElseThrow(() -> new StudyNotFoundException(caseId));
        ChatConversation conversation = repository.findChatConversationById(conversationId)
            .filter(candidate -> candidate.studyId().equals(study.id()))
            .orElseThrow(this::conversationNotFound);
        List<ChatConversationDetailDto.Message> messages = repository.findChatMessagesByConversationId(conversation.id()).stream()
            .map(message -> new ChatConversationDetailDto.Message(
                message.id(), message.role(), message.content(), message.createdAt(), message.model(), message.provider(), message.promptVersion(), message.contextVersion(), isOutdated(message, study),
                "ASSISTANT".equals(message.role()) ? (isOutdated(message, study) ? "DRAFT_OUTDATED" : "DRAFT_PENDING_REVIEW") : null
            ))
            .toList();
        return new ChatConversationDetailDto(conversation.id(), caseId, conversation.createdAt(), messages);
    }

    private boolean isOutdated(ChatMessage message, Study study) {
        return "ASSISTANT".equals(message.role())
            && message.contextVersion() != null
            && study.contextVersion() != null
            && !message.contextVersion().equals(study.contextVersion());
    }

    /**
     * Stage 81-C: the last {@code resolvedHistoryWindowTurns()} turns (USER+ASSISTANT pairs) of
     * an existing conversation, mapped to the provider's role vocabulary. Never includes a
     * historical system-prompt message (81: "NO incluir system prompts históricos completos") --
     * only the current {@link ChatSystemPrompts#V1} (added separately, once, by the caller) is
     * ever sent as the system role.
     */
    private List<LlmChatMessage> historyWindow(String conversationId) {
        List<ChatMessage> all = repository.findChatMessagesByConversationId(conversationId);
        int windowMessages = properties.resolvedHistoryWindowTurns() * 2;
        int from = Math.max(0, all.size() - windowMessages);
        return all.subList(from, all.size()).stream()
            .map(message -> new LlmChatMessage("ASSISTANT".equals(message.role()) ? "assistant" : "user", message.content()))
            .toList();
    }

    private RunReviewException conversationNotFound() {
        return new RunReviewException(HttpStatus.NOT_FOUND, "CHAT_CONVERSATION_NOT_FOUND", "Conversacion no encontrada.");
    }

    private String validateQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "CHAT_QUESTION_REQUIRED", "La pregunta no puede estar vacia.");
        }
        String trimmed = question.trim();
        if (trimmed.length() > MAX_QUESTION_LENGTH) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "CHAT_QUESTION_TOO_LONG", "La pregunta supera el largo maximo permitido.");
        }
        return trimmed;
    }

    private String writeContextJson(ChatContextResult context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not serialize chat context", ex);
        }
    }

    private void audit(String action, String studyId, String traceId, List<String> contextSources) {
        if (auditService == null) return;
        try {
            auditService.record("system", action, studyId, traceId == null || traceId.isBlank() ? "unavailable" : traceId, Map.of("contextSources", contextSources));
        } catch (RuntimeException ignored) {
            // Chat must not fail just because audit persistence is unavailable.
        }
    }
}
