package com.github.thxmasj.backend.backoffice;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.backoffice.Sessions.Reply;
import com.github.thxmasj.backend.http.Headers;
import com.github.thxmasj.backend.http.Headers.Field;
import org.junit.jupiter.api.Test;

class ReplyTest {

  /// A reply is stored with its headers a list of fields, where a name may repeat
  @Test
  void replyIsReadAsStored() {
    var reply = new Reply(303, Headers.builder().add("Location", "/").add("Set-Cookie", "a=1").add("Set-Cookie", "b=2").build(), "");
    var stored = DataType.objectMapper.writeValueAsString(reply);
    assertThat(DataType.objectMapper.readValue(stored, Reply.class)).isEqualTo(reply);
  }

  /// A reply stored before a header could have several values has an object of a value per name
  @Test
  void replyStoredWithAValuePerNameIsRead() {
    var stored = "{\"status\":303,\"headers\":{\"Location\":\"/\",\"Set-Cookie\":\"a=1\"},\"body\":\"\"}";
    assertThat(DataType.objectMapper.readValue(stored, Reply.class).headers())
        .containsExactly(new Field("Location", "/"), new Field("Set-Cookie", "a=1"));
  }
}
