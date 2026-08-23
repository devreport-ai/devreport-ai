package ai.devreport.backend.credential.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import ai.devreport.backend.credential.domain.UserAiCredential;
import ai.devreport.backend.integration.ai.AiProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UserAiCredentialRepository extends JpaRepository<UserAiCredential, UUID> {

	List<UserAiCredential> findAllByUserIdOrderByProviderAsc(UUID userId);

	Optional<UserAiCredential> findByUserIdAndProvider(UUID userId, AiProvider provider);

	boolean existsByUserIdAndProvider(UUID userId, AiProvider provider);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select credential from UserAiCredential credential "
		+ "where credential.userId = :userId and credential.provider = :provider")
	Optional<UserAiCredential> findForUpdate(UUID userId, AiProvider provider);
}
