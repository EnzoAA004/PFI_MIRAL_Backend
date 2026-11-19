package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.dto.AiInputResponseDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class AiBackendServiceInputScopeTest {
    @Test
    void persistsUploadedInputUnderItsAlreadyClaimedCaseForResourceAuthorization() {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        assertTrue(repository.claimStudyOwner("CASE-RAW-INPUT", "owner-a"));
        AiServiceOperations ai = (AiServiceOperations) Proxy.newProxyInstance(
            AiServiceOperations.class.getClassLoader(),
            new Class<?>[] {AiServiceOperations.class},
            (proxy, method, args) -> method.getName().equals("uploadInput")
                ? new AiInputResponseDto("input-raw-axial", "CASE-RAW-INPUT", "axial", "npy", 1024)
                : null);
        AiBackendService service = new AiBackendService(
            ai, null, null, null, null, null, null, repository,
            (RunAssetContentStorage) null, null, null);

        service.uploadInput(new MockMultipartFile("file", "axial_sample_input.npy", "application/octet-stream", new byte[] {1, 2, 3}),
            "CASE-RAW-INPUT", "axial");

        assertEquals("CASE-RAW-INPUT", repository.resolveStudyCaseId("input-raw-axial").orElseThrow());
        assertTrue(repository.isStudyOwner("CASE-RAW-INPUT", "owner-a"));
    }
}
