package ai.devreport.backend.usage.infrastructure;

import ai.devreport.backend.usage.domain.UsageEvent;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageEventRepository extends JpaRepository<UsageEvent, UUID>, UsageEventRepositoryCustom {

	long deleteByOccurredAtBefore(Instant occurredAt);
}
