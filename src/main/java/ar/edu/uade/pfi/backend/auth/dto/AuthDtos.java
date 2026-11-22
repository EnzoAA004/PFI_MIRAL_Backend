package ar.edu.uade.pfi.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;

public final class AuthDtos {
  private AuthDtos() {}

  public record RegisterRequest(
      @NotBlank String fullName,
      @Email @NotBlank String email,
      @NotBlank String password,
      String licenseNumber,
      String specialty,
      String institution) {}

  public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

  public record VerifyRequest(@NotBlank String challengeId, @NotBlank String code) {}

  public record RefreshRequest(@NotBlank String refreshToken) {}

  public record SettingsRequest(Boolean twoFactorEnabled, Boolean onboardingCompleted) {}

  public record ChangePasswordRequest(
      @NotBlank String currentPassword, @NotBlank String newPassword) {}

  public record RejectRegistrationRequest(@Email @NotBlank String email, @NotBlank String reason) {}

  public record ForgotPasswordRequest(@Email @NotBlank String email) {}

  public record ResetPasswordRequest(
      @NotBlank String challengeId, @NotBlank String code, @NotBlank String newPassword) {}

  public record ProfileChangeRequest(String note) {}

  public record ProfileChangeUnlockRequest(@NotBlank String token) {}

  public record ProfileChangeSubmitRequest(
      @NotBlank String fullName, String licenseNumber, String institution) {}

  /** status: NONE, REQUESTED, AUTHORIZED, UNLOCKED o PENDING_REVIEW. */
  public record ProfileChangeResponse(
      String status,
      String note,
      Instant expiresAt,
      Instant requestedAt,
      String previousFullName,
      String previousLicenseNumber,
      String previousInstitution) {}

  public record AdminProfileChangeItem(
      String email,
      String fullName,
      String licenseNumber,
      String institution,
      String status,
      String note,
      Instant requestedAt,
      String previousFullName,
      String previousLicenseNumber,
      String previousInstitution) {}

  public record AdminProfileChangeAuthorizeRequest(@Email @NotBlank String email) {}

  public record AdminProfileChangeDecisionRequest(@Email @NotBlank String email, boolean approve) {}

  public record ApprovalRequest(@Email @NotBlank String email, boolean approved) {}

  /**
   * Strict-by-design: ignoreUnknown=false rejects (400) any extra field — in particular
   * "roles"/"admin"/"authorities"/"permissions"/"verified"/"approved"/ "password"/"passwordHash" —
   * instead of silently ignoring them. Only email and activated are ever accepted.
   */
  @JsonIgnoreProperties(ignoreUnknown = false)
  public record ProfessionalActivationRequest(@Email @NotBlank String email, boolean activated) {}

  public record ProfessionalActivationResponse(
      String email, String status, boolean verified, boolean approved, List<String> roles) {}

  public record PendingAuthResponse(
      String challengeId,
      String channel,
      int expiresInSeconds,
      String message,
      String devVerificationCode) {}

  public record UserResponse(
      String id,
      String fullName,
      String email,
      String licenseNumber,
      String specialty,
      String institution,
      List<String> roles,
      boolean verified,
      boolean approved,
      boolean twoFactorEnabled,
      boolean onboardingCompleted) {}

  public record TokenResponse(
      String accessToken,
      String refreshToken,
      String tokenType,
      long expiresInSeconds,
      UserResponse user) {}
}
