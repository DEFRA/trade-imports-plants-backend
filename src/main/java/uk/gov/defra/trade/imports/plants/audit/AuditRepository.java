package uk.gov.defra.trade.imports.plants.audit;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditRepository extends MongoRepository<Audit, String> {
}
