package ai.devreport.backend.integration.ai;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "ai.service.mock", havingValue = "false", matchIfMissing = true)
class HttpAiServiceClient implements AiServiceClient {

	static final String PROVIDER_API_KEY_HEADER = "X-Provider-Api-Key";

	private final RestClient client;
	private final String internalToken;
	private final ObjectMapper objectMapper;

	HttpAiServiceClient(
		@Value("${ai.service.url}") String serviceUrl,
		@Value("${ai.service.connect-timeout}") Duration connectTimeout,
		@Value("${ai.service.response-timeout}") Duration responseTimeout,
		@Value("${ai.service.internal-token}") String internalToken,
		ObjectMapper objectMapper
	) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(responseTimeout);
		this.client = RestClient.builder().baseUrl(serviceUrl).requestFactory(requestFactory).build();
		this.internalToken = internalToken;
		this.objectMapper = objectMapper;
	}

	@Override
	public AiHealthResponse health() {
		return execute(() -> client.get().uri("/health").retrieve().body(AiHealthResponse.class),
			response -> response != null && "UP".equalsIgnoreCase(response.status()), Operation.HEALTH);
	}

	@Override
	public ReportDocument generate(GenerationRequest request, GenerationBundle bundle, String providerApiKey) {
		if (internalToken.isBlank()) {
			throw Failure.AI_SERVICE_UNAVAILABLE.exception();
		}
		var body = new LinkedMultiValueMap<String, Object>();
		body.add("request", jsonPart(request));
		body.add("manifest", filePart(bundle.manifest(), "manifest", "manifest.json",
			MediaType.APPLICATION_JSON_VALUE));
		bundle.files().forEach(file -> body.add("files",
			filePart(file.path(), "files", file.relativePath(), file.contentType())));
		return execute(() -> client.post().uri("/internal/ai/reports/generate")
			.header("X-Internal-Token", internalToken)
			.headers(headers -> providerKeyHeader(headers, providerApiKey))
			.contentType(MediaType.MULTIPART_FORM_DATA).body(body)
			.retrieve().body(ReportDocument.class), response -> response != null, Operation.GENERATE);
	}

	@Override
	public void verifyCredential(AiProvider provider, String providerApiKey) {
		if (internalToken.isBlank()) {
			throw Failure.AI_SERVICE_UNAVAILABLE.exception();
		}
		execute(() -> client.post().uri("/internal/ai/credentials/verify")
			.header("X-Internal-Token", internalToken)
			.headers(headers -> providerKeyHeader(headers, providerApiKey))
			.contentType(MediaType.APPLICATION_JSON).body(Map.of("provider", provider.name()))
			.retrieve().body(JsonNode.class),
			response -> response != null && response.path("valid").asBoolean(false), Operation.VERIFY_CREDENTIAL);
	}

	private static void providerKeyHeader(HttpHeaders headers, String providerApiKey) {
		if (providerApiKey != null && !providerApiKey.isBlank()) {
			headers.set(PROVIDER_API_KEY_HEADER, providerApiKey);
		}
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

	private <T> T execute(Supplier<T> request, Predicate<T> validResponse, Operation operation) {
		try {
			T response = request.get();
			if (!validResponse.test(response)) {
				throw operation.invalidResponse().exception();
			}
			return response;
		} catch (AiServiceException exception) {
			throw exception;
		} catch (RestClientResponseException exception) {
			throw mapRemoteFailure(exception.getResponseBodyAsString(), operation.fallback());
		} catch (ResourceAccessException exception) {
			if (hasCause(exception, HttpTimeoutException.class)) {
				throw operation.timeout().exception(exception);
			}
			throw Failure.AI_SERVICE_UNAVAILABLE.exception(exception);
		} catch (RestClientException ignored) {
			throw operation.invalidResponse().exception();
		}
	}

	private AiServiceException mapRemoteFailure(String responseBody, Failure fallback) {
		if (responseBody == null || responseBody.isBlank()) {
			return fallback.exception();
		}
		String code = null;
		try {
			JsonNode payload = objectMapper.readTree(responseBody);
			JsonNode codeNode = payload == null ? null : payload.get("code");
			if (codeNode != null && codeNode.isString()) {
				code = codeNode.asText();
			}
		} catch (JacksonException ignored) {
			// AI Service 오류 본문은 내부 정보일 수 있으므로 원문을 로그나 응답에 남기지 않는다.
		}
		if (code == null) {
			return fallback.exception();
		}

		return switch (code) {
			case "AI_UNAUTHORIZED" -> Failure.AI_SERVICE_UNAVAILABLE.exception();
			case "AI_INVALID_REQUEST", "AI_MODEL_NOT_ALLOWED" -> Failure.GENERATION_REQUEST_INVALID.exception();
			case "AI_CREDENTIAL_INVALID" -> Failure.AI_CREDENTIAL_INVALID.exception();
			case "AI_PROVIDER_RATE_LIMITED" -> Failure.PROVIDER_QUOTA_EXCEEDED.exception();
			case "AI_FILE_PROCESSING_FAILED", "AI_GENERATION_FAILED", "AI_INVALID_RESPONSE" ->
				Failure.GENERATION_FAILED.exception();
			case "AI_TIMEOUT" -> Failure.GENERATION_TIMEOUT.exception();
			case "AI_UNAVAILABLE" -> Failure.AI_SERVICE_UNAVAILABLE.exception();
			default -> fallback.exception();
		};
	}

	private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
		for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
			if (type.isInstance(cause)) {
				return true;
			}
		}
		return false;
	}

	private enum Operation {
		HEALTH(
			Failure.AI_SERVICE_UNAVAILABLE,
			Failure.AI_SERVICE_TIMEOUT,
			Failure.AI_SERVICE_INVALID_RESPONSE
		),
		GENERATE(
			Failure.GENERATION_FAILED,
			Failure.GENERATION_TIMEOUT,
			Failure.GENERATION_FAILED
		),
		VERIFY_CREDENTIAL(
			Failure.AI_CREDENTIAL_VERIFICATION_FAILED,
			Failure.AI_CREDENTIAL_VERIFICATION_FAILED,
			Failure.AI_CREDENTIAL_VERIFICATION_FAILED
		);

		private final Failure fallback;
		private final Failure timeout;
		private final Failure invalidResponse;

		Operation(Failure fallback, Failure timeout, Failure invalidResponse) {
			this.fallback = fallback;
			this.timeout = timeout;
			this.invalidResponse = invalidResponse;
		}

		Failure fallback() {
			return fallback;
		}

		Failure timeout() {
			return timeout;
		}

		Failure invalidResponse() {
			return invalidResponse;
		}
	}

	private enum Failure {
		GENERATION_REQUEST_INVALID(HttpStatus.BAD_REQUEST, "GENERATION_REQUEST_INVALID",
			"보고서 생성 요청이 올바르지 않습니다."),
		GENERATION_FAILED(HttpStatus.BAD_GATEWAY, "GENERATION_FAILED", "보고서 생성에 실패했습니다."),
		GENERATION_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "GENERATION_TIMEOUT", "보고서 생성 시간이 초과되었습니다."),
		AI_SERVICE_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "AI_SERVICE_UNAVAILABLE", "AI 서비스에 연결할 수 없습니다."),
		AI_SERVICE_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AI_SERVICE_TIMEOUT", "AI 서비스 응답 시간이 초과되었습니다."),
		AI_SERVICE_INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "AI_SERVICE_INVALID_RESPONSE",
			"AI 서비스 응답을 확인할 수 없습니다."),
		AI_CREDENTIAL_INVALID(HttpStatus.BAD_REQUEST, "AI_CREDENTIAL_INVALID",
			"등록된 API Key를 provider가 거부했습니다. 키를 확인해 주세요."),
		AI_CREDENTIAL_VERIFICATION_FAILED(HttpStatus.BAD_GATEWAY, "AI_CREDENTIAL_VERIFICATION_FAILED",
			"API Key를 확인할 수 없습니다."),
		PROVIDER_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "PROVIDER_QUOTA_EXCEEDED",
			"provider 사용량 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.");

		private final HttpStatus status;
		private final String code;
		private final String message;

		Failure(HttpStatus status, String code, String message) {
			this.status = status;
			this.code = code;
			this.message = message;
		}

		AiServiceException exception() {
			return exception(null);
		}

		AiServiceException exception(Throwable cause) {
			return new AiServiceException(status, code, message, cause);
		}
	}
}
