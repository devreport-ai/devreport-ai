package ai.devreport.backend.auth.infrastructure;

import ai.devreport.backend.auth.domain.User;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, UUID> {
	Optional<User> findByEmail(String email);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select user from User user where user.id = :id")
	Optional<User> findForUpdate(UUID id);
}
