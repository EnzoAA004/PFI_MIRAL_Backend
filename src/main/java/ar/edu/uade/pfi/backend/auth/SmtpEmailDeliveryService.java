package ar.edu.uade.pfi.backend.auth;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Implementación SMTP de {@link EmailDeliveryService}, activa solo con {@code
 * pfi.auth.email-delivery=smtp}. La configuración se lee de {@code pfi.mail.*} (env {@code
 * PFI_MAIL_*}); la aplicación se niega a arrancar si falta un valor requerido, para que un
 * despliegue a medio configurar nunca pueda afirmar haber enviado un código.
 */
@Service
@ConditionalOnProperty(name = "pfi.auth.email-delivery", havingValue = "smtp")
public class SmtpEmailDeliveryService implements EmailDeliveryService {
  private static final Logger log = LoggerFactory.getLogger(SmtpEmailDeliveryService.class);

  private final JavaMailSenderImpl sender;
  private String supportEmail = "";
  private final InternetAddress from;

  public SmtpEmailDeliveryService(
      @Value("${pfi.mail.host:}") String host,
      @Value("${pfi.mail.port:587}") int port,
      @Value("${pfi.mail.username:}") String username,
      @Value("${pfi.mail.password:}") String password,
      @Value("${pfi.mail.from:}") String fromAddress,
      @Value("${pfi.mail.from-name:MIRA·L}") String fromName,
      @Value("${pfi.mail.starttls:true}") boolean startTls,
      @Value("${pfi.mail.ssl:false}") boolean ssl,
      @Value("${pfi.mail.timeout-ms:10000}") int timeoutMs) {
    requireSet("pfi.mail.host (PFI_MAIL_HOST)", host);
    requireSet("pfi.mail.username (PFI_MAIL_USERNAME)", username);
    requireSet("pfi.mail.password (PFI_MAIL_PASSWORD)", password);
    requireSet("pfi.mail.from (PFI_MAIL_FROM)", fromAddress);
    try {
      this.from = new InternetAddress(fromAddress.trim(), fromName, StandardCharsets.UTF_8.name());
      this.from.validate();
    } catch (Exception e) {
      throw new IllegalStateException("pfi.mail.from is not a valid e-mail address");
    }
    this.sender = new JavaMailSenderImpl();
    sender.setHost(host.trim());
    sender.setPort(port);
    sender.setUsername(username);
    sender.setPassword(password);
    sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
    Properties p = sender.getJavaMailProperties();
    p.put("mail.transport.protocol", ssl ? "smtps" : "smtp");
    p.put("mail.smtp.auth", "true");
    p.put("mail.smtp.starttls.enable", String.valueOf(startTls && !ssl));
    p.put("mail.smtp.starttls.required", String.valueOf(startTls && !ssl));
    p.put("mail.smtp.ssl.enable", String.valueOf(ssl));
    p.put("mail.smtp.connectiontimeout", String.valueOf(timeoutMs));
    p.put("mail.smtp.timeout", String.valueOf(timeoutMs));
    p.put("mail.smtp.writetimeout", String.valueOf(timeoutMs));
    log.info("SMTP e-mail delivery enabled via {}:{} (credentials not logged)", host.trim(), port);
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setSupportEmail(@Value("${pfi.support.email:}") String value) {
    this.supportEmail = value == null ? "" : value.trim();
  }

  @Override
  public void sendProfileChangeToken(String toEmail, String token, Duration validFor) {
    send(
        toEmail,
        "MIRA·L - código para editar tu perfil",
        "Un administrador autorizó el cambio de los datos de tu perfil.\n\n"
            + "Tu código es: " + token + "\n\n"
            + "Ingresalo en Configuración dentro de los próximos " + validFor.toMinutes() + " minutos. "
            + "Si no pediste este cambio, ignorá este mensaje.\n\n"
            + "Al guardar los cambios, tu cuenta queda en solo lectura hasta que un administrador los revise.\n");
  }

  @Override
  public void sendRegistrationRejected(String toEmail, String fullName, String reason) {
    send(
        toEmail,
        "MIRA·L - tu registro no fue aprobado",
        "Hola " + (fullName == null || fullName.isBlank() ? "" : fullName) + ",\n\n"
            + "Un administrador revisó tu registro profesional en MIRA·L y todavía no pudo aprobarlo.\n\n"
            + "Motivo: " + reason + "\n\n"
            + "Podés corregir los datos y volver a registrarte, o responder a este correo para aclararlo.\n");
  }

  @Override
  public void notifyProfileChangeRequested(String doctorName, String doctorEmail, String note) {
    String target = supportEmail.isBlank() ? from.getAddress() : supportEmail;
    send(
        target,
        "MIRA·L - solicitud de cambio de datos de perfil",
        "El profesional " + doctorName + " (" + doctorEmail + ") pidió cambiar los datos de su perfil.\n\n"
            + "Motivo: " + (note == null || note.isBlank() ? "(sin detalle)" : note) + "\n\n"
            + "Para autorizarlo, entrá a la pantalla Profesionales de MIRA·L (sección «Cambios de perfil»).\n");
  }

  private void send(String toEmail, String subject, String text) {
    try {
      InternetAddress to = new InternetAddress(toEmail, true);
      MimeMessage message = sender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
      helper.setFrom(from);
      helper.setTo(to);
      helper.setSubject(subject);
      helper.setText(text, false);
      sender.send(message);
      log.info("Notification e-mail handed to SMTP server");
    } catch (Exception e) {
      log.warn("Notification e-mail could not be sent: {}", e.getClass().getSimpleName());
      throw new EmailDeliveryException("SMTP delivery failed", e);
    }
  }

  private static void requireSet(String name, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required when pfi.auth.email-delivery=smtp");
    }
  }

  @Override
  public void sendVerificationCode(String toEmail, String purpose, String code, Duration validFor) {
    try {
      InternetAddress to = new InternetAddress(toEmail, true); // estricto: rechaza CR/LF y grupos
      MimeMessage message = sender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
      helper.setFrom(from);
      helper.setTo(to);
      helper.setSubject("MIRA·L - código de verificación");
      helper.setText(body(purpose, code, validFor), false);
      sender.send(message);
      log.info("Verification e-mail handed to SMTP server (purpose={})", purpose);
    } catch (Exception e) {
      // Nunca incluir el código, el destinatario ni la contraseña SMTP en logs ni excepciones.
      log.warn(
          "Verification e-mail could not be sent (purpose={}): {}",
          purpose,
          e.getClass().getSimpleName());
      throw new EmailDeliveryException("SMTP delivery failed", e);
    }
  }

  static String body(String purpose, String code, Duration validFor) {
    String action =
        "LOGIN".equals(purpose)
            ? "iniciar sesión"
            : "RESET".equals(purpose) ? "restablecer tu contraseña" : "verificar tu correo profesional";
    return "Tu código para " + action + " en MIRA·L es: " + code + "\n\n"
        + "Vence en " + validFor.toMinutes() + " minutos. Si no lo solicitaste, ignorá este mensaje.\n\n"
        + "MIRA·L es un prototipo académico; este correo no contiene datos de pacientes.\n";
  }
}
