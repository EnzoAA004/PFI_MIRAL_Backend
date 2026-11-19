package ar.edu.uade.pfi.backend.security;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.web.filter.StudyAccessInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import java.util.List;
import java.time.Instant;
import ar.edu.uade.pfi.backend.domain.Study;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StudyAccessIsolationTest {
    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final RoleAuthorizationService authorization = new RoleAuthorizationService(new AuditService(repository), repository);

    @Test
    void ownerCanReadAndWriteWhileUnassignedUserCannotAccessCase() {
        HttpServletRequest owner = request("owner-1");
        HttpServletRequest other = request("other-1");
        authorization.claimOrRequireStudyWrite(owner, "case-private");
    Instant now = Instant.now();
    repository.saveStudy(new Study("study-1", "case-private", "registered", now, now));

        assertDoesNotThrow(() -> authorization.requireStudyRead(owner, "case-private"));
        assertDoesNotThrow(() -> authorization.requireStudyWrite(owner, "case-private"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> authorization.requireStudyRead(other, "case-private"));
        assertFalse(repository.claimStudyOwner("case-private", "other-1"));
    }

    @Test
    void explicitReadGrantDoesNotPermitMutationAndScopesVisibleWorklist() {
        HttpServletRequest owner = request("owner-1");
        HttpServletRequest reader = request("reader-1");
        authorization.claimOrRequireStudyWrite(owner, "case-a");
        authorization.claimOrRequireStudyWrite(owner, "case-b");
    Instant now = Instant.now();
    repository.saveStudy(new Study("study-a", "case-a", "registered", now, now));
    repository.saveStudy(new Study("study-b", "case-b", "registered", now, now));
        authorization.grantStudyAccess(owner, "case-a", "reader-1", "READ");

        assertDoesNotThrow(() -> authorization.requireStudyRead(reader, "case-a"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> authorization.requireStudyWrite(reader, "case-a"));
        assertEquals(List.of("case-a"), authorization.accessibleStudyCaseIds(reader));
        assertFalse(repository.hasStudyAccess("case-b", "reader-1", false));
    }

    @Test
    void legacyStudyCannotBeClaimedByFirstCallerThroughUploadFlow() {
        Instant now = Instant.now();
        repository.saveStudy(new Study("study-id", "legacy-case", "registered", now, now));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> authorization.claimOrRequireStudyWrite(request("first-caller"), "legacy-case"));
        assertTrue(repository.findAccessibleCaseIds("first-caller").isEmpty());
    }

    @Test
    void missingAndUnassignedHistoricalStudiesFailClosed() {
        Instant now = Instant.now();
        repository.saveStudy(new Study("legacy-unassigned", "legacy-unassigned-case", "registered", now, now));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> authorization.requireStudyRead(request("any-user"), "never-existed"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> authorization.requireStudyRead(request("any-user"), "legacy-unassigned-case"));
        assertTrue(repository.claimStudyOwner("new-upload-case", "upload-owner"));
        assertDoesNotThrow(() -> authorization.requireStudyWrite(request("upload-owner"), "new-upload-case"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> authorization.requireStudyWrite(request("other-user"), "new-upload-case"));
    }

    @Test
    void secondaryBodyResourceMustBelongToTheRequestedCase() {
        Instant now = Instant.now();
        repository.saveStudy(new Study("study-a", "case-a", "registered", now, now));
        repository.saveStudy(new Study("study-b", "case-b", "registered", now, now));
        repository.saveInput(new InputResource("row-a", "study-a", "sagittal", "input-a", "DICOM", 1, now));
        repository.saveInput(new InputResource("row-b", "study-b", "sagittal", "input-b", "DICOM", 1, now));
        assertTrue(repository.claimStudyOwner("case-a", "owner-a"));
        assertTrue(repository.claimStudyOwner("case-b", "owner-b"));

        assertDoesNotThrow(() -> authorization.requireResourceBelongsToStudy(request("owner-a"), "input-a", "case-a", true));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> authorization.requireResourceBelongsToStudy(request("owner-a"), "input-b", "case-a", true));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> authorization.requireResourceBelongsToStudy(request("owner-a"), "unknown-input", "case-a", true));
    }

    @Test
    void onlyOwnerCanManageExplicitStudyGrants() {
        Instant now = Instant.now();
        repository.saveStudy(new Study("study-owned", "case-owned", "registered", now, now));
        assertTrue(repository.claimStudyOwner("case-owned", "owner"));
        authorization.grantStudyAccess(request("owner"), "case-owned", "writer", "WRITE");

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> authorization.grantStudyAccess(request("writer"), "case-owned", "reader", "READ"));
        assertFalse(repository.hasStudyAccess("case-owned", "reader", false));
    }

    @Test
    void indirectImageAndResultRoutesResolveBackToTheOwningStudy() {
        Instant now = Instant.now();
        repository.saveStudy(new Study("study-images", "case-images", "registered", now, now));
        repository.saveInput(new InputResource("input-row", "study-images", "sagittal", "input-private", "DICOM", 1, now));
        assertTrue(repository.claimStudyOwner("case-images", "owner-1"));
        StudyAccessInterceptor interceptor = new StudyAccessInterceptor(authorization, repository);

        MockHttpServletRequest owner = request("owner-1");
        owner.setRequestURI("/api/ai/series/input-private/slices/0");
        owner.setMethod("GET");
        owner.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, java.util.Map.of("inputId", "input-private"));
        assertDoesNotThrow(() -> interceptor.preHandle(owner, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest otherUser = request("other-1");
        otherUser.setRequestURI("/api/ai/series/input-private/slices/0");
        otherUser.setMethod("GET");
        otherUser.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, java.util.Map.of("inputId", "input-private"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> interceptor.preHandle(otherUser, new MockHttpServletResponse(), new Object()));
    }

    private MockHttpServletRequest request(String subject) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE,
            new TokenService.Claims(subject, subject + "@example.test", subject, List.of("DOCTOR")));
        return request;
    }
}
