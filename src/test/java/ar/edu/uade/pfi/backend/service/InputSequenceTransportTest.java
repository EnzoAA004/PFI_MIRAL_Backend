package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.InputSequence;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.AiInputResponseDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * La secuencia declarada viaja desde la carga por plano hasta el catálogo del estudio, y una carga sin
 * declaración sigue siendo la de siempre.
 */
class InputSequenceTransportTest {
  private final AiServiceOperations ai = mock(AiServiceOperations.class);
  private final AiBackendService service = new AiBackendService(ai, mock(ReviewStoreService.class));
  private final MockMultipartFile file =
      new MockMultipartFile("file", "vol.mha", "application/octet-stream", new byte[] {1, 2, 3, 4});

  private static AiInputResponseDto response(AiInputResponseDto.Sequence sequence) {
    return new AiInputResponseDto("inp-1", "CASE-1", "sagittal", "mha", 4, sequence);
  }

  /* ------------------------------------------------------------------------------ carga */

  @Test
  void anUploadWithoutDeclarationKeepsTheOriginalCallAndDoesNotSendTheField() {
    when(ai.uploadInput(any(), eq("CASE-1"), eq("sagittal"))).thenReturn(response(null));
    service.uploadInput(file, "CASE-1", "sagittal", null, null);
    service.uploadInput(file, "CASE-1", "sagittal", "unknown", null);
    service.uploadInput(file, "CASE-1", "sagittal", "", "");
    org.mockito.Mockito.verify(ai, org.mockito.Mockito.times(3)).uploadInput(any(), eq("CASE-1"), eq("sagittal"));
    verify(ai, never()).uploadInput(any(), any(), any(), any(), any());
  }

  @Test
  void aDeclaredSequenceIsForwardedAndReturnedWithItsProvenance() {
    when(ai.uploadInput(any(), eq("CASE-1"), eq("sagittal"), eq("t1"), eq("")))
        .thenReturn(response(new AiInputResponseDto.Sequence("t1", "user_declared", null)));
    AiInputResponseDto result = service.uploadInput(file, "CASE-1", "sagittal", " T1 ", null);
    assertEquals("t1", result.sequence().value());
    assertEquals("user_declared", result.sequence().source());
  }

  @Test
  void otherIsForwardedWithItsDescription() {
    when(ai.uploadInput(any(), eq("CASE-1"), eq("sagittal"), eq("other"), eq("STIR sagital")))
        .thenReturn(response(new AiInputResponseDto.Sequence("other", "user_declared", "STIR sagital")));
    assertEquals("STIR sagital", service.uploadInput(file, "CASE-1", "sagittal", "other", " STIR sagital ").sequence().description());
  }

  @Test
  void invalidDeclarationsAreRejectedBeforeCallingTheAiModule() {
    for (String[] bad : new String[][] {
      {"t3", null}, {"flair", null}, {"other", null}, {"other", "  "}, {"t1", "T1 rapido"}, {"other", "a".repeat(81)}, {"other", "a\nb"}
    }) {
      ResponseStatusException error =
          assertThrows(ResponseStatusException.class, () -> service.uploadInput(file, "CASE-1", "sagittal", bad[0], bad[1]), String.join("|", String.valueOf(bad[0]), String.valueOf(bad[1])));
      assertEquals(400, error.getStatusCode().value());
    }
    verifyNoInteractions(ai);
  }

  /* ------------------------------------------------------------------------------ catálogo del estudio */

  private InMemoryStudyRepository repository = new InMemoryStudyRepository();
  private final StudyRunService studyRunService = new StudyRunService(repository);
  private final MultiplanarRunPersistenceService persistence = new MultiplanarRunPersistenceService(studyRunService);

  private List<InputResource> persistWith(Map<String, Object> input, String caseId) {
    MultiplanarRunRequestDto request =
        new MultiplanarRunRequestDto(caseId, "inp-" + caseId, null, null, null, "sagittal_spider", "axial_t2_alkafri", false, Map.of("inferenceMode", "real_baseline"));
    Map<String, Object> plane = new LinkedHashMap<>(input);
    plane.put("inputId", "inp-" + caseId);
    CanonicalPlaneRun sagittal =
        new CanonicalPlaneRun("sag-" + caseId, "sagittal", "ready", "real_baseline", false, null,
            Map.of("modelKey", "sagittal_spider"), plane, Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
    Map<String, CanonicalPlaneRun> planes = new LinkedHashMap<>();
    planes.put("sagittal", sagittal);
    CanonicalMultiplanarRun response =
        new CanonicalMultiplanarRun("completed", "pfi.multiplanar-run.v2", "multi-" + caseId, "trace-" + caseId, caseId, "sagittal_only",
            "real_baseline", "real_baseline", List.of("sagittal"), List.of("sagittal"), false, null, Map.of(), planes, Map.of(), Map.of(),
            Map.of("status", "pending"), new CanonicalMultiplanarRun.Governance(true, true, true, false));
    persistence.persistSuccessfulRun(request, response);
    Study study = studyRunService.findStudyByCaseId(caseId).orElseThrow();
    return studyRunService.findInputs(study);
  }

  @Test
  void theRunsDeclaredSequenceIsPersistedWithTheStudysInput() {
    List<InputResource> inputs = persistWith(Map.of("sequence", Map.of("value", "t1", "source", "user_declared")), "CASE-A");
    assertEquals(1, inputs.size());
    InputResource input = inputs.get(0);
    assertEquals("t1", input.weighting());
    assertEquals("user_declared", input.sequenceSource());
    assertEquals(new InputSequence("t1", "user_declared", ""), InputSequence.fromStored(input.weighting(), input.sequenceSource(), input.sequenceDescription()));
  }

  @Test
  void otherKeepsItsDescriptionAndNeverBecomesAWeighting() {
    InputResource input = persistWith(Map.of("sequence", Map.of("value", "other", "source", "user_declared", "description", "STIR")), "CASE-B").get(0);
    assertEquals("other", input.weighting());
    assertEquals("STIR", input.sequenceDescription());
    assertTrue(!InputSequence.fromStored(input.weighting(), input.sequenceSource(), input.sequenceDescription()).isComparableWeighting());
  }

  @Test
  void aRunWithoutSequenceOrWithAnUnknownOneLeavesTheInputUnknownNotT2() {
    InputResource none = persistWith(Map.of(), "CASE-C").get(0);
    assertEquals("", none.weighting());
    assertEquals("", none.sequenceSource());
    InputResource unknown = persistWith(Map.of("sequence", Map.of("value", "unknown", "source", "unknown")), "CASE-D").get(0);
    assertEquals("", unknown.weighting());
    assertEquals(InputSequence.unknown(), InputSequence.fromStored(unknown.weighting(), unknown.sequenceSource(), unknown.sequenceDescription()));
  }

  @Test
  void anIncoherentPublishedSequenceIsStoredAsUnknown() {
    InputResource input = persistWith(Map.of("sequence", Map.of("value", "t2", "source", "unknown")), "CASE-E").get(0);
    assertEquals("", input.weighting());
  }
}
