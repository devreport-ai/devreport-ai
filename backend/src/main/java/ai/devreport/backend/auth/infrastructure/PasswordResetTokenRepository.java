package ai.devreport.backend.auth.infrastructure;

import ai.devreport.backend.auth.domain.PasswordResetToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
	@Query("select token.user.id from PasswordResetToken token where token.tokenHash = :tokenHash")
	Optional<UUID> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<PasswordResetToken> findByTokenHash(String tokenHash);

	@Modifying
	@Query("update PasswordResetToken token set token.usedAt = :usedAt "
		+ "where token.user.id = :userId and token.usedAt is null")
	int useAllByUserId(@Param("userId") UUID userId, @Param("usedAt") Instant usedAt);
}
