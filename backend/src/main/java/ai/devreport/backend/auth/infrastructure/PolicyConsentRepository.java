package ai.devreport.backend.auth.infrastructure;

import ai.devreport.backend.auth.domain.PolicyConsent;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyConsentRepository extends JpaRepository<PolicyConsent, UUID> {
}
