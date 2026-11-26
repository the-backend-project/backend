package com.github.thxmasj.backend.core.database;

import com.github.thxmasj.backend.core.EntityType;
import com.github.thxmasj.backend.core.EventType;

/// No entity is indexed by the key
public class EntityGroupNotInitialised extends RuntimeException {

  public EntityGroupNotInitialised(EntityType entityType, EventType<?, ?> indexType, Object key) {
    super("No " + entityType.name() + " indexed by " + indexType.name() + "=" + key);
  }

}
