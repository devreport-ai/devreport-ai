package ai.devreport.backend.export.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfReportRendererTest {

	@TempDir
	Path exportRoot;

	@Test
	void requiresAnHttpPrintUrlWithAnExportIdPlaceholder() {
		assertThat(new PdfReportRenderer(exportRoot.toString(), "", Duration.ofSeconds(1)).isConfigured())
			.isFalse();
		assertThat(new PdfReportRenderer(exportRoot.toString(), "ftp://localhost/print/{exportId}", Duration.ofSeconds(1))
			.isConfigured()).isFalse();
		assertThat(new PdfReportRenderer(exportRoot.toString(),
			"http://localhost/print/report-exports/{exportId}", Duration.ofSeconds(1)).isConfigured()).isTrue();
	}

	@Test
	void rendersAnHtmlPageAfterItSignalsReady() throws Exception {
		UUID exportId = UUID.randomUUID();
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/print/report-exports/" + exportId, exchange -> {
			byte[] body = """
				<!doctype html>
				<html data-print-state="ready">
				<head><style>@page { size: A4; margin: 10mm; }</style></head>
				<body>DevReport PDF 테스트</body>
				</html>
				""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
			exchange.sendResponseHeaders(200, body.length);
			try (var output = exchange.getResponseBody()) {
				output.write(body);
			}
		});
		server.start();
		try {
			String printUrl = "http://localhost:" + server.getAddress().getPort()
				+ "/print/report-exports/{exportId}";
			PdfReportRenderer renderer = new PdfReportRenderer(exportRoot.toString(), printUrl,
				Duration.ofSeconds(10));
			Path pdf = renderer.render(exportId, "test-token");
			assertThat(Files.readAllBytes(pdf)).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
		} finally {
			server.stop(0);
		}
	}
}
