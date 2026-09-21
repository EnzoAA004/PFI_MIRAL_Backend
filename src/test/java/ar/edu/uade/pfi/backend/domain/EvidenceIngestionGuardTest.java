package ar.edu.uade.pfi.backend.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EvidenceIngestionGuardTest {

    @Test
    void refusesGeometryFromP10_7BlockedPathologyInference() {
        assertThrows(IllegalArgumentException.class,
            () -> EvidenceIngestionGuard.requireNotBlockedPathologyInference("blocked_preprocessing_parity"));
    }

    @Test
    void allowsIngestionWhenPathologyInferenceIsNotTheBlockedStatus() {
        assertDoesNotThrow(() -> EvidenceIngestionGuard.requireNotBlockedPathologyInference(null));
        assertDoesNotThrow(() -> EvidenceIngestionGuard.requireNotBlockedPathologyInference("not_requested"));
    }

    @Test
    void refuses67CGeometryNotFlaggedExperimental() {
        assertThrows(IllegalArgumentException.class,
            () -> EvidenceIngestionGuard.requireExperimentalFlagForAxialPairing(true, false));
    }

    @Test
    void allows67CGeometryWhenFlaggedExperimental() {
        assertDoesNotThrow(() -> EvidenceIngestionGuard.requireExperimentalFlagForAxialPairing(true, true));
    }

    @Test
    void nonExperimentalAxialPairingNeverRequiresTheFlag() {
        assertDoesNotThrow(() -> EvidenceIngestionGuard.requireExperimentalFlagForAxialPairing(false, false));
    }
}
