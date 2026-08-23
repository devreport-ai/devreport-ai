package ai.devreport.backend.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PasswordResetEmailSenderTest {

	@Test
	void sendsResetLinkThroughResendApiWithoutPuttingTokenInTheRequestPath() throws Exception {
		AtomicReference<String> path = new AtomicReference<>();
		AtomicReference<String> authorization = new AtomicReference<>();
		AtomicReference<String> body = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/emails", exchange -> {
			path.set(exchange.getRequestURI().toString());
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		server.start();
		try {
			PasswordResetEmailSender sender = new PasswordResetEmailSender(
				"http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(1), Duration.ofSeconds(1),
				"resend-key", "DevReport AI <no-reply@mail.example.com>", "https://app.example.com/");

			sender.send("user@example.com", "raw-reset-token");

			assertThat(path.get()).isEqualTo("/emails");
			assertThat(authorization.get()).isEqualTo("Bearer resend-key");
			assertThat(body.get())
				.contains("user@example.com", "DevReport AI <no-reply@mail.example.com>",
					"https://app.example.com/reset-password#token=raw-reset-token");
		} finally {
			server.stop(0);
		}
	}
}
