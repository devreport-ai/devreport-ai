package ai.devreport.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfigurationSource;

class SecurityConfigTest {

	@Test
	void emptyCorsAllowlistDisablesCrossOriginRequests() {
		CorsConfigurationSource source = new SecurityConfig().corsConfigurationSource("");
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Origin", "https://frontend.example");

		assertThat(source.getCorsConfiguration(request).getAllowedOrigins()).isEmpty();
	}
}
