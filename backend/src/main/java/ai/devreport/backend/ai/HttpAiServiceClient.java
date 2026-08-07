package ai.devreport.backend.ai;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(name = "ai.service.mock", havingValue = "false", matchIfMissing = true)
class HttpAiServiceClient implements AiServiceClient {

	private final RestClient client;

	HttpAiServiceClient(
		@Value("${ai.service.url}") String serviceUrl,
		@Value("${ai.service.connect-timeout}") Duration connectTimeout,
		@Value("${ai.service.response-timeout}") Duration responseTimeout
	) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(responseTimeout);
		this.client = RestClient.builder().baseUrl(serviceUrl).requestFactory(requestFactory).build();
	}

	@Override
	public AiHealthResponse health() {
		try {
			AiHealthResponse response = client.get().uri("/health").retrieve().body(AiHealthResponse.class);
			if (response == null || !"UP".equalsIgnoreCase(response.status())) {
				throw failure("AI_SERVICE_INVALID_RESPONSE", "AI 서비스 응답을 확인할 수 없습니다.", null);
			}
			return response;
		} catch (AiServiceException exception) {
			throw exception;
		} catch (RestClientResponseException ignored) {
			throw failure("AI_SERVICE_ERROR", "AI 서비스가 요청 처리에 실패했습니다.", null);
		} catch (ResourceAccessException exception) {
			if (hasCause(exception, HttpTimeoutException.class)) {
				throw timeout(exception);
			}
			throw failure("AI_SERVICE_UNAVAILABLE", "AI 서비스에 연결할 수 없습니다.", exception);
		} catch (RestClientException ignored) {
			throw failure("AI_SERVICE_INVALID_RESPONSE", "AI 서비스 응답을 확인할 수 없습니다.", null);
		}
	}

	private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
		for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
			if (type.isInstance(cause)) {
				return true;
			}
		}
		return false;
	}

	private static AiServiceException timeout(Throwable cause) {
		return new AiServiceException(HttpStatus.GATEWAY_TIMEOUT, "AI_SERVICE_TIMEOUT",
			"AI 서비스 응답 시간이 초과되었습니다.", cause);
	}

	private static AiServiceException failure(String code, String message, Throwable cause) {
		return new AiServiceException(HttpStatus.BAD_GATEWAY, code, message, cause);
	}
}
