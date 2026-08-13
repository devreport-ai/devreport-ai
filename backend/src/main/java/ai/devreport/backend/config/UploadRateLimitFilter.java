package ai.devreport.backend.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import ai.devreport.backend.auth.application.AuthenticatedUser;
import ai.devreport.backend.common.error.ErrorResponse;
import ai.devreport.backend.usage.application.RateLimitService;
import ai.devreport.backend.usage.domain.UsageLimitException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.http.server.PathContainer;
import tools.jackson.databind.ObjectMapper;

final class UploadRateLimitFilter extends OncePerRequestFilter {

	private final PathPattern uploadPath = new PathPatternParser().parse("/api/projects/{projectId}/files");
	private final RateLimitService rateLimits;
	private final ObjectMapper objectMapper;

	UploadRateLimitFilter(RateLimitService rateLimits, ObjectMapper objectMapper) {
		this.rateLimits = rateLimits;
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		if (!isUpload(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
			filterChain.doFilter(request, response);
			return;
		}

		try {
			rateLimits.checkUpload(AuthenticatedUser.id(jwt));
		} catch (UsageLimitException exception) {
			writeError(response, exception);
			return;
		}
		filterChain.doFilter(request, response);
	}

	private boolean isUpload(HttpServletRequest request) {
		if (!HttpMethod.POST.name().equals(request.getMethod())) {
			return false;
		}
		String contextPath = request.getContextPath();
		String path = request.getRequestURI().substring(contextPath.length());
		return uploadPath.matches(PathContainer.parsePath(path));
	}

	private void writeError(HttpServletResponse response, UsageLimitException exception) throws IOException {
		response.setStatus(exception.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(
			exception.code(), exception.getMessage(), exception.details()));
	}
}
