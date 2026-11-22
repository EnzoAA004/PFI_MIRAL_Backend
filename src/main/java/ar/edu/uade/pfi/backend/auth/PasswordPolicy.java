package ar.edu.uade.pfi.backend.auth;

import java.util.regex.Pattern;

/**
 * Reglas de contraseña y de correo de las cuentas profesionales. Se aplican al registrarse, al cambiar
 * la contraseña y al restablecerla, para que no haya tres criterios distintos.
 */
final class PasswordPolicy {
  static final int MIN_LENGTH = 8;
  static final int MAX_LENGTH = 128;

  /** usuario@dominio.tld: dominio con al menos un punto y extensión de 2 o más letras. Cualquier dominio sirve. */
  private static final Pattern EMAIL =
      Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}$");

  private PasswordPolicy() {}

  /** @return el problema en español, o {@code null} si la contraseña cumple. */
  static String problem(String password, String email) {
    if (password == null || password.length() < MIN_LENGTH) {
      return "La contraseña debe tener al menos " + MIN_LENGTH + " caracteres.";
    }
    if (password.length() > MAX_LENGTH) {
      return "La contraseña no puede superar los " + MAX_LENGTH + " caracteres.";
    }
    if (Character.isDigit(password.codePointAt(0))) {
      return "La contraseña no puede empezar con un número.";
    }
    if (password.chars().noneMatch(Character::isLetter) || password.chars().noneMatch(Character::isDigit)) {
      return "La contraseña debe combinar letras y números.";
    }
    if (password.chars().noneMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c))) {
      return "La contraseña debe incluir al menos un carácter especial (por ejemplo ! ? - _ . # $ %).";
    }
    if (email != null && password.equalsIgnoreCase(email)) {
      return "La contraseña no puede ser tu correo.";
    }
    return null;
  }

  static boolean validEmail(String email) {
    return email != null && email.length() <= 254 && EMAIL.matcher(email.trim()).matches();
  }
}
