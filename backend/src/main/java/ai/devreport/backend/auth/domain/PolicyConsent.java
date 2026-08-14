package ai.devreport.backend.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 회원가입 당시 동의한 정책 버전과 서버 기록 시각. */
@Entity
@Table(name = "user_policy_consents")
public class PolicyConsent {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(name = "privacy_policy_version", nullable = false, length = 50)
	private String privacyPolicyVersion;

	@Column(name = "terms_of_service_version", nullable = false, length = 50)
	private String termsOfServiceVersion;

	@Column(name = "consented_at", nullable = false)
	private Instant consentedAt;

	protected PolicyConsent() {
	}

	public PolicyConsent(User user, String privacyPolicyVersion, String termsOfServiceVersion,
		Instant consentedAt) {
		this.id = UUID.randomUUID();
		this.user = user;
		this.privacyPolicyVersion = privacyPolicyVersion;
		this.termsOfServiceVersion = termsOfServiceVersion;
		this.consentedAt = consentedAt;
	}

	public User getUser() {
		return user;
	}

	public String getPrivacyPolicyVersion() {
		return privacyPolicyVersion;
	}

	public String getTermsOfServiceVersion() {
		return termsOfServiceVersion;
	}

	public Instant getConsentedAt() {
		return consentedAt;
	}
}
