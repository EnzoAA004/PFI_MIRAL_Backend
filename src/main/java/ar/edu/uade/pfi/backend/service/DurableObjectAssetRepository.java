package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.DurableObjectAsset;
import java.util.List;
import java.util.Optional;

public interface DurableObjectAssetRepository {

    DurableObjectAsset save(DurableObjectAsset asset);

    Optional<DurableObjectAsset> findById(String assetId);

    /** Asset {@code study_input} más reciente de un estudio -- se usa para reprocesar sin volver a subir. */
    Optional<DurableObjectAsset> findLatestStudyInput(String studyId, String caseId);

    List<DurableObjectAsset> findByStudyId(String studyId);
}
