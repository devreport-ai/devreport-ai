package ai.devreport.backend.auth.application;

import ai.devreport.backend.auth.infrastructure.PasswordResetEmailSender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class PasswordResetWorker {

	private static final Logger LOGGER = LoggerFactory.getLogger(PasswordResetWorker.class);

	private final AuthService authService;
	private final PasswordResetEmailSender emailSender;

	PasswordResetWorker(AuthService authService, PasswordResetEmailSender emailSender) {
		this.authService = authService;
		this.emailSender = emailSender;
	}

	@Async("passwordResetEmailExecutor")
	public void process(String email) {
		try {
			authService.createPasswordReset(email)
				.ifPresent(mail -> emailSender.send(mail.email(), mail.token()));
		} catch (RuntimeException exception) {
			LOGGER.error("Password reset processing failed", exception);
		}
	}
}
