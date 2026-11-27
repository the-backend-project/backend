package com.github.thxmasj.backend.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/// What the libraries write in their debug output, which is on stdout, has no data of the events, which may be secrets
public class DebugOutputTest {

  static final EventType<String, String> SecretSet = EventType.of("Secret set", UUID.randomUUID(), DataType.string());
  static final EntityType Vault = EntityType.of("Vault", UUID.randomUUID(), java.util.Map::of);

  @Test
  public void anInputEventIsWrittenWithoutItsData() {
    var written = new InputEvent<>(SecretSet, "s3cret").toString();
    assertTrue(written.contains("Secret set"), written);
    assertFalse(written.contains("s3cret"), written);
  }

  @Test
  public void aDelayedTriggerIsWrittenWithoutItsData() {
    var written = new Change.DelayedTrigger<>(Vault, UUID.randomUUID(), SecretSet, 2, "s3cret", ZonedDateTime.now(), "c").toString();
    assertTrue(written.contains("Secret set"), written);
    assertFalse(written.contains("s3cret"), written);
  }

}
