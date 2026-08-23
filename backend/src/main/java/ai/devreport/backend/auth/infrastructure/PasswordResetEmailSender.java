package ai.devreport.backend.auth.infrastructure;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import ai.devreport.backend.auth.application.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class PasswordResetEmailSender {
	private static final Logger LOGGER = LoggerFactory.getLogger(PasswordResetEmailSender.class);

	private final RestClient client;
	private final String apiKey;
	private final String from;
	private final String publicAppUrl;

	PasswordResetEmailSender(
		@Value("${auth.password-reset.resend-api-url}") String apiUrl,
		@Value("${auth.password-reset.connect-timeout}") Duration connectTimeout,
		@Value("${auth.password-reset.response-timeout}") Duration responseTimeout,
		@Value("${auth.password-reset.resend-api-key}") String apiKey,
		@Value("${auth.password-reset.from}") String from,
		@Value("${auth.password-reset.public-app-url}") String publicAppUrl
	) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(responseTimeout);
		this.client = RestClient.builder().baseUrl(apiUrl).requestFactory(requestFactory).build();
		this.apiKey = apiKey;
		this.from = from;
		this.publicAppUrl = publicAppUrl.replaceFirst("/+$", "");
	}

	public void send(String to, String token) {
		if (apiKey.isBlank()) {
			return;
		}
		String link = publicAppUrl + "/reset-password#token=" + token;
		try {
			client.post()
				.uri("/emails")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
				.header(HttpHeaders.USER_AGENT, "devreport-ai-backend")
				.header("Idempotency-Key", AuthService.hash(token))
				.body(Map.of(
					"from", from,
					"to", new String[] {to},
					"subject", "DevReport AI 비밀번호 재설정",
					"text", "아래 링크가 만료되기 전에 비밀번호를 재설정해 주세요.\n\n" + link
				))
				.retrieve()
				.toBodilessEntity();
		} catch (RuntimeException exception) {
			// 원문 토큰과 이메일 주소는 로그에 남기지 않는다.
			LOGGER.error("Password reset email delivery failed", exception);
		}
	}
}
