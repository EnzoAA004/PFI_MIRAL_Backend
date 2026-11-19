package ar.edu.uade.pfi.backend.config;

import ar.edu.uade.pfi.backend.web.filter.StudyAccessInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.repository.StudyRepository;

@Configuration
@ConditionalOnBean({RoleAuthorizationService.class, StudyRepository.class})
public class StudyAccessWebConfiguration implements WebMvcConfigurer {
  private final StudyAccessInterceptor studyAccessInterceptor;

  public StudyAccessWebConfiguration(StudyAccessInterceptor studyAccessInterceptor) {
    this.studyAccessInterceptor = studyAccessInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(studyAccessInterceptor).addPathPatterns("/api/**");
  }
}
