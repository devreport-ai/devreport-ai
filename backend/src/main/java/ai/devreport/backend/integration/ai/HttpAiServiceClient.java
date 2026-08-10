package ai.devreport.backend.integration.ai;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

import ai.devreport.backend.report.domain.ReportDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(name = "ai.service.mock", havingValue = "false", matchIfMissing = true)
class HttpAiServiceClient implements AiServiceClient {

	private final RestClient client;
	private final String internalToken;

	HttpAiServiceClient(
		@Value("${ai.service.url}") String serviceUrl,
		@Value("${ai.service.connect-timeout}") Duration connectTimeout,
		@Value("${ai.service.response-timeout}") Duration responseTimeout,
		@Value("${ai.service.internal-token}") String internalToken
	) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(responseTimeout);
		this.client = RestClient.builder().baseUrl(serviceUrl).requestFactory(requestFactory).build();
		this.internalToken = internalToken;
	}

	@Override
	public AiHealthResponse health() {
		return execute(() -> client.get().uri("/health").retrieve().body(AiHealthResponse.class),
			response -> response != null && "UP".equalsIgnoreCase(response.status()));
	}

	@Override
	public ReportDocument generate(GenerationRequest request, GenerationBundle bundle) {
		if (internalToken.isBlank()) {
			throw failure("AI_SERVICE_UNAVAILABLE", "AI 서비스 내부 인증이 설정되지 않았습니다.", null);
		}
		var body = new LinkedMultiValueMap<String, Object>();
		body.add("request", jsonPart(request));
		body.add("manifest", filePart(bundle.manifest(), "manifest", "manifest.json",
			MediaType.APPLICATION_JSON_VALUE));
		bundle.files().forEach(file -> body.add("files",
			filePart(file.path(), "files", file.relativePath(), file.contentType())));
		return execute(() -> client.post().uri("/internal/ai/reports/generate")
			.header("X-Internal-Token", internalToken).contentType(MediaType.MULTIPART_FORM_DATA).body(body)
			.retrieve().body(ReportDocument.class), response -> response != null);
	}

	private static HttpEntity<Object> jsonPart(Object value) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return new HttpEntity<>(value, headers);
	}

	private static HttpEntity<FileSystemResource> filePart(Path path, String name, String filename,
		String contentType) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType(contentType));
		headers.setContentDisposition(ContentDisposition.formData().name(name).filename(filename).build());
		return new HttpEntity<>(new FileSystemResource(path), headers);
	}

	private static <T> T execute(Supplier<T> request, Predicate<T> validResponse) {
		try {
			T response = request.get();
			if (!validResponse.test(response)) {
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
