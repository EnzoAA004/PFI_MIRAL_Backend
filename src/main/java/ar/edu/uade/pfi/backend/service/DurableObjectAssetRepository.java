package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.DurableObjectAsset;
import java.util.List;
import java.util.Optional;

public interface DurableObjectAssetRepository {

    DurableObjectAsset save(DurableObjectAsset asset);

    Optional<DurableObjectAsset> findById(String assetId);

    /** Most recent {@code study_input} asset for a study -- used to reprocess without re-upload. */
    Optional<DurableObjectAsset> findLatestStudyInput(String studyId);

    List<DurableObjectAsset> findByStudyId(String studyId);
}
