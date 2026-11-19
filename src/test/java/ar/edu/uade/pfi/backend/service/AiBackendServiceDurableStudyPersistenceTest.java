package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.domain.DurableObjectAsset;
import ar.edu.uade.pfi.backend.storage.FilesystemObjectStorage;
import ar.edu.uade.pfi.backend.storage.ObjectStorage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Covers Cloud P0B: a study ZIP upload is persisted durably (item 5, "DICOM durable
 * flow") and can be reprocessed from that durable copy without a new client upload
 * (item 5, "reutilizarse sin nuevo upload" / item "DICOM reprocess result").
 */
class AiBackendServiceDurableStudyPersistenceTest {

    @TempDir
    Path tempDir;

    private static final byte[] ZIP_BYTES = {'P', 'K', 3, 4, 9, 9, 9};

    @Test
    void uploadStudyPersistsRawBytesDurablyKeyedByStudyId() {
        AiServiceOperations ai = mock(AiServiceOperations.class);
        when(ai.uploadStudy(any(), eq("CASE-001"))).thenReturn(Map.ofEntries(
            Map.entry("caseId", "CASE-001"),
            Map.entry("studyId", "study-durable-1"),
            Map.entry("sagittal", Map.of("plane", "sagittal", "inputId", "inp_a"))
        ));
        InMemoryDurableObjectAssetRepository repo = new InMemoryDurableObjectAssetRepository();
        ObjectStorage storage = new FilesystemObjectStorage(tempDir);
        AiBackendService service = service(ai, storage, repo);
        MockMultipartFile file = new MockMultipartFile("file", "study.zip", "application/zip", ZIP_BYTES);

        service.uploadStudy(file, "CASE-001");

        DurableObjectAsset stored = repo.findLatestStudyInput("study-durable-1", "CASE-001").orElseThrow();
        assertEquals("study_input", stored.artifactType());
        assertEquals("CASE-001", stored.caseId());
        assertEquals("filesystem", stored.storageProvider());
        assertTrue(storage.exists(stored.objectKey()));
    }

    @Test
    void reprocessStudyReplaysDurableBytesWithoutANewClientUpload() {
        AiServiceOperations ai = mock(AiServiceOperations.class);
        when(ai.uploadStudy(any(), eq("CASE-001"))).thenReturn(Map.ofEntries(
            Map.entry("caseId", "CASE-001"),
            Map.entry("studyId", "study-durable-2"),
            Map.entry("sagittal", Map.of("plane", "sagittal", "inputId", "inp_a"))
        ));
        InMemoryDurableObjectAssetRepository repo = new InMemoryDurableObjectAssetRepository();
        ObjectStorage storage = new FilesystemObjectStorage(tempDir);
        AiBackendService service = service(ai, storage, repo);
        MockMultipartFile file = new MockMultipartFile("file", "study.zip", "application/zip", ZIP_BYTES);
        service.uploadStudy(file, "CASE-001");

        service.reprocessStudy("study-durable-2", "CASE-001");

        verify(ai, org.mockito.Mockito.times(2)).uploadStudy(any(), eq("CASE-001"));
        MultipartFile replay = captureSecondUploadCall(ai);
    }

    @Test
    void reprocessStudyReturns404WhenNoDurableInputWasEverPersisted() {
        AiServiceOperations ai = mock(AiServiceOperations.class);
        InMemoryDurableObjectAssetRepository repo = new InMemoryDurableObjectAssetRepository();
        ObjectStorage storage = new FilesystemObjectStorage(tempDir);
        AiBackendService service = service(ai, storage, repo);

        ResponseStatusException ex = org.junit.jupiter.api.Assertions.assertThrows(
            ResponseStatusException.class, () -> service.reprocessStudy("study-none", "CASE-001"));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void uploadStudySucceedsEvenIfDurablePersistenceIsNotConfigured() {
        AiServiceOperations ai = mock(AiServiceOperations.class);
        when(ai.uploadStudy(any(), eq("CASE-001"))).thenReturn(Map.ofEntries(
            Map.entry("caseId", "CASE-001"),
            Map.entry("studyId", "study-no-durable"),
            Map.entry("sagittal", Map.of("plane", "sagittal", "inputId", "inp_a"))
        ));
        AiBackendService service = new AiBackendService(ai, mock(ReviewStoreService.class));
        MockMultipartFile file = new MockMultipartFile("file", "study.zip", "application/zip", ZIP_BYTES);

        var response = service.uploadStudy(file, "CASE-001");

        assertEquals("study-no-durable", response.studyId());
    }

    private MultipartFile captureSecondUploadCall(AiServiceOperations ai) {
        org.mockito.ArgumentCaptor<MultipartFile> captor = org.mockito.ArgumentCaptor.forClass(MultipartFile.class);
        verify(ai, org.mockito.Mockito.atLeastOnce()).uploadStudy(captor.capture(), any());
        return captor.getValue();
    }

    private AiBackendService service(AiServiceOperations ai, ObjectStorage storage, DurableObjectAssetRepository repo) {
        return new AiBackendService(
            ai,
            mock(ReviewStoreService.class),
            null, null, null, null, null, null,
            (RunAssetContentStorage) null,
            null,
            null,
            AiBackendService.DEFAULT_STUDY_UPLOAD_BYTES,
            storage,
            repo
        );
    }

    private static final class InMemoryDurableObjectAssetRepository implements DurableObjectAssetRepository {
        private final List<DurableObjectAsset> assets = new ArrayList<>();

        @Override
        public DurableObjectAsset save(DurableObjectAsset asset) {
            assets.add(asset);
            return asset;
        }

        @Override
        public Optional<DurableObjectAsset> findById(String assetId) {
            return assets.stream().filter(a -> a.assetId().equals(assetId)).findFirst();
        }

        @Override
        public Optional<DurableObjectAsset> findLatestStudyInput(String studyId, String caseId) {
            return assets.stream()
                .filter(a -> a.studyId().equals(studyId) && caseId.equals(a.caseId()) && "study_input".equals(a.artifactType()))
                .max(java.util.Comparator.comparing(DurableObjectAsset::createdAt));
        }

        @Override
        public List<DurableObjectAsset> findByStudyId(String studyId) {
            return assets.stream().filter(a -> a.studyId().equals(studyId)).toList();
        }
    }
}
