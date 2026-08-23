package ai.devreport.backend.auth.infrastructure;

import ai.devreport.backend.auth.domain.RefreshToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<RefreshToken> findByTokenHash(String tokenHash);

	@Modifying
	@Query("update RefreshToken token set token.revokedAt = :revokedAt "
		+ "where token.user.id = :userId and token.revokedAt is null")
	int revokeAllByUserId(@Param("userId") UUID userId, @Param("revokedAt") Instant revokedAt);
}
