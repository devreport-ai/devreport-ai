package ai.devreport.backend.config;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.HttpServletResponse;

import ai.devreport.backend.common.error.ErrorResponse;
import ai.devreport.backend.usage.application.RateLimitService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {
	public static final String JWT_ISSUER = "devreport-ai";

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
		RateLimitService rateLimits) throws Exception {
		return http
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/api/auth/signup", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
					"/api/report-exports/*/render-data", "/api/report-exports/*/files/*",
					"/actuator/health", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
				.anyRequest().authenticated())
			.oauth2ResourceServer(resourceServer -> resourceServer
				.jwt(jwt -> {})
				.authenticationEntryPoint((request, response, exception) ->
					writeError(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
						"UNAUTHORIZED", "인증이 필요합니다.")))
			.addFilterAfter(new UploadRateLimitFilter(rateLimits, objectMapper), BearerTokenAuthenticationFilter.class)
			.exceptionHandling(exceptions -> exceptions
				.accessDeniedHandler((request, response, exception) ->
					writeError(response, objectMapper, HttpServletResponse.SC_FORBIDDEN,
						"FORBIDDEN", "접근 권한이 없습니다.")))
			.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	JwtEncoder jwtEncoder(@Value("${auth.jwt-secret}") String secret) {
		return NimbusJwtEncoder.withSecretKey(secretKey(secret)).algorithm(MacAlgorithm.HS256).build();
	}

	@Bean
	JwtDecoder jwtDecoder(@Value("${auth.jwt-secret}") String secret) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(secret))
			.macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(JWT_ISSUER));
		return decoder;
	}

	private static SecretKey secretKey(String secret) {
		if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException("JWT_SECRET은 32바이트 이상이어야 합니다.");
		}
		return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	private static void writeError(HttpServletResponse response, ObjectMapper objectMapper,
		int status, String code, String message) throws java.io.IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(code, message, null));
	}
}
