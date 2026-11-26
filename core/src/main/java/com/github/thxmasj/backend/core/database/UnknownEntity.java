package com.github.thxmasj.backend.core.database;

import com.github.thxmasj.backend.core.EntityType;
import com.github.thxmasj.backend.core.EventType;
import java.util.UUID;

public class UnknownEntity extends RuntimeException {

  private final EntityType entityType;
  private final UUID id;

  public UnknownEntity(EntityType entityType, EventType<?, ?> idType, Object id) {
    super(String.format("Unknown entity: %s/%s=%s", entityType.name(), idType.name(), id));
    this.entityType = entityType;
    this.id = null;
  }

  public UnknownEntity(EntityType entityType, UUID id) {
    super(String.format("Unknown entity: %s/id=%s", entityType.name(), id));
    this.entityType = entityType;
    this.id = id;
  }

  public UUID id() {
    return id;
  }

  public EntityType entityType() {
    return entityType;
  }

}
