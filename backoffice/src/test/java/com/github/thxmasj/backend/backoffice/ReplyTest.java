package com.github.thxmasj.backend.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.backoffice.Sessions.Reply;
import com.github.thxmasj.backend.http.Headers;
import com.github.thxmasj.backend.http.Headers.Field;
import org.junit.jupiter.api.Test;

class ReplyTest {

  /// A reply whose header names don't repeat is stored with an object of a value per name, which a node not yet
  /// upgraded reads as well, and one where a name repeats with a list of fields
  @Test
  void replyIsReadAsStored() {
    var single = new Reply(303, Headers.builder().add("Location", "/").add("Set-Cookie", "a=1").build(), "");
    var stored = DataType.objectMapper.writeValueAsString(single);
    assertThat(stored).contains("\"headers\":{\"Location\":\"/\",\"Set-Cookie\":\"a=1\"}");
    assertThat(DataType.objectMapper.readValue(stored, Reply.class)).isEqualTo(single);
    var repeated = new Reply(303, Headers.builder().add("Location", "/").add("Set-Cookie", "a=1").add("set-cookie", "b=2").build(), "");
    stored = DataType.objectMapper.writeValueAsString(repeated);
    assertThat(stored).contains("\"headers\":[");
    assertThat(DataType.objectMapper.readValue(stored, Reply.class)).isEqualTo(repeated);
  }

  /// A reply stored before a header could have several values has an object of a value per name
  @Test
  void replyStoredWithAValuePerNameIsRead() {
    var stored = "{\"status\":303,\"headers\":{\"Location\":\"/\",\"Set-Cookie\":\"a=1\"},\"body\":\"\"}";
    assertThat(DataType.objectMapper.readValue(stored, Reply.class).headers())
        .containsExactly(new Field("Location", "/"), new Field("Set-Cookie", "a=1"));
  }

  /// A header without a name or value is not read as the text "null", but fails
  @Test
  void replyWithAHeaderWithoutValueFails() {
    assertThatThrownBy(() -> DataType.objectMapper.readValue("{\"status\":303,\"headers\":[{\"name\":\"Location\"}],\"body\":\"\"}", Reply.class))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DataType.objectMapper.readValue("{\"status\":303,\"headers\":{\"Location\":null},\"body\":\"\"}", Reply.class))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }
}
