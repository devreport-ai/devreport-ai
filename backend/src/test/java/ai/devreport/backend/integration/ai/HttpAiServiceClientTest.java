package ai.devreport.backend.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import ai.devreport.backend.report.domain.ReportDocument;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class HttpAiServiceClientTest {
	@TempDir
	Path temporaryDirectory;

	private HttpServer server;

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void readsFastApiHealthResponse() throws Exception {
		startServer(exchange -> respond(exchange, 200, """
			{"status":"UP","service":"devreport-ai-service","version":"0.1.0",
			 "env":"local","mockReport":true,"contractsFound":true}
			"""));

		AiHealthResponse response = client(Duration.ofSeconds(1)).health();

		assertThat(response.status()).isEqualTo("UP");
		assertThat(response.service()).isEqualTo("devreport-ai-service");
		assertThat(response.contractsFound()).isTrue();
	}

	@Test
	void sendsGenerationRequestAndReadsReportDocument() throws Exception {
		AtomicReference<String> requestBody = new AtomicReference<>();
		startServer(exchange -> {
			assertThat(exchange.getRequestMethod()).isEqualTo("POST");
			assertThat(exchange.getRequestURI().getPath()).isEqualTo("/internal/ai/reports/generate");
			assertThat(exchange.getRequestHeaders().getFirst("X-Internal-Token"))
				.isEqualTo("test-internal-token");
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(exchange, 200, """
				{"metadata":{"title":"생성 보고서"},"sections":[]}
				""");
		});

		UUID fileId = UUID.randomUUID();
		Path manifest = temporaryDirectory.resolve("manifest.json");
		Path document = temporaryDirectory.resolve("notes.txt");
		Files.writeString(manifest, "{\"version\":1,\"files\":[]}");
		Files.writeString(document, "분석 자료");
		var bundle = new GenerationBundle(temporaryDirectory, manifest,
			List.of(new GenerationBundle.FilePart(document, "documents/" + fileId + "/notes.txt", "text/plain")));
		ReportDocument response = client(Duration.ofSeconds(1)).generate(new GenerationRequest(List.of(fileId),
			Map.of(), "요약해 줘"), bundle);

		assertThat(response.metadata().title()).isEqualTo("생성 보고서");
		assertThat(requestBody.get()).contains("name=\"request\"")
			.contains("name=\"manifest\"; filename=\"manifest.json\"")
			.contains("name=\"files\"; filename=\"documents/" + fileId + "/notes.txt\"")
			.contains("\"instructions\":\"요약해 줘\"").contains("분석 자료");
	}

	@Test
	void rejectsGenerationWithoutInternalToken() {
		var client = new HttpAiServiceClient("http://127.0.0.1", Duration.ofSeconds(1),
			Duration.ofSeconds(1), " ");
		var bundle = new GenerationBundle(temporaryDirectory, temporaryDirectory.resolve("manifest.json"),
			List.of());

		assertThatThrownBy(() -> client.generate(
			new GenerationRequest(List.of(UUID.randomUUID()), Map.of(), "요약"), bundle))
			.isInstanceOfSatisfying(AiServiceException.class,
				exception -> assertThat(exception.code()).isEqualTo("AI_SERVICE_UNAVAILABLE"));
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "AI_SERVICE_INTEGRATION_URL", matches = ".+")
	void connectsToRunningFastApi() {
		var client = new HttpAiServiceClient(System.getenv("AI_SERVICE_INTEGRATION_URL"),
			Duration.ofSeconds(3), Duration.ofSeconds(3), "integration-test-token");

		assertThat(client.health().service()).isEqualTo("devreport-ai-service");
	}

	@Test
	void convertsServerError() throws Exception {
		startServer(exchange -> respond(exchange, 503, "unavailable"));

		assertThatThrownBy(() -> client(Duration.ofSeconds(1)).health())
			.isInstanceOfSatisfying(AiServiceException.class, exception -> {
				assertThat(exception.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
				assertThat(exception.code()).isEqualTo("AI_SERVICE_ERROR");
			});
	}

	@Test
	void convertsResponseTimeout() throws Exception {
		startServer(exchange -> {
			try {
				Thread.sleep(300);
				respond(exchange, 200, "{\"status\":\"UP\"}");
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		});

		assertThatThrownBy(() -> client(Duration.ofMillis(50)).health())
			.isInstanceOfSatisfying(AiServiceException.class, exception -> {
				assertThat(exception.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
				assertThat(exception.code()).isEqualTo("AI_SERVICE_TIMEOUT");
			});
	}

	private HttpAiServiceClient client(Duration responseTimeout) {
		return new HttpAiServiceClient("http://127.0.0.1:" + server.getAddress().getPort(),
			Duration.ofSeconds(1), responseTimeout, "test-internal-token");
	}

	private void startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", handler);
		server.start();
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (var response = exchange.getResponseBody()) {
			response.write(bytes);
		}
	}
}
