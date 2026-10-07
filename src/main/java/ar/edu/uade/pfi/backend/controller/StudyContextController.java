package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.dto.StudyContextDto;
import ar.edu.uade.pfi.backend.service.ProfessionalAccessAuditService;
import ar.edu.uade.pfi.backend.service.StudyContextService;
import ar.edu.uade.pfi.backend.web.error.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/studies")
@Tag(name = "Estudios", description = "Paciente asociado y contexto clinico de un estudio.")
public class StudyContextController {
  private final StudyContextService studyContextService;
  private final ProfessionalAccessAuditService accessAuditService;

  public StudyContextController(
      StudyContextService studyContextService, ProfessionalAccessAuditService accessAuditService) {
    this.studyContextService = studyContextService;
    this.accessAuditService = accessAuditService;
  }

  @Operation(summary = "Obtiene el paciente asociado y el contexto clinico del estudio")
  @ApiResponse(responseCode = "200", description = "Paciente (si hay) y contexto clinico persistido.")
  @ApiResponse(
      responseCode = "404",
      description = "Estudio inexistente.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @GetMapping("/{caseId}/context")
  public StudyContextDto get(@PathVariable String caseId, HttpServletRequest request) {
    accessAuditService.record(
        request, "access_study_context", "Contexto clinico de estudio consultado caseId=" + caseId);
    return studyContextService.get(caseId);
  }
}
