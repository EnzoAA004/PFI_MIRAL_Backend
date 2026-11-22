package ar.edu.uade.pfi.backend.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Headers de respuesta solo del Backend. Esta API nunca sirve el HTML del frontend, por lo que el CSP acá está
 * acotado a lo que necesita una API pura de JSON/assets binarios (sin directivas script/style que solo
 * importarían para un documento HTML) — el CSP propio del frontend se configura por separado en Vercel.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class SecurityHeadersFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
    String path = request.getRequestURI();
    if (isSensitive(path)) {
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("Pragma", "no-cache");
    }
    filterChain.doFilter(request, response);
  }

  private boolean isSensitive(String path) {
    return path != null && (path.startsWith("/api/auth") || path.equals("/api/system/diagnostics"));
  }
}
