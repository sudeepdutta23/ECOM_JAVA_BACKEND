package com.ecommerce.backend.common.persistence;

import com.ecommerce.backend.common.util.UuidV7;
import java.util.UUID;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.uuid.UuidValueGenerator;

/**
 * Plugs {@link UuidV7} into Hibernate's {@code @UuidGenerator(algorithm = ...)}.
 *
 * <p>Hibernate assigns the ID just before INSERT. Until then the ID is {@code null}, which is how
 * Spring Data's {@code save()} knows the entity is new and calls {@code persist} instead of
 * {@code merge}. Assigning IDs by hand in constructors would make every save run an extra SELECT.
 */
public class UuidV7Generator implements UuidValueGenerator {

    @Override
    public UUID generateUuid(SharedSessionContractImplementor session) {
        return UuidV7.generate();
    }
}
