package com.github.thxmasj.backend.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.thxmasj.backend.backoffice.Sessions.Reply;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.http.Headers;
import org.junit.jupiter.api.Test;

class ReplyTest {

  /// A reply is stored with its headers a list of fields, where a name may repeat
  @Test
  void replyIsReadAsStored() {
    var reply = new Reply(303, Headers.of("Location", "/", "Set-Cookie", "a=1", "set-cookie", "b=2"), "");
    var stored = DataType.objectMapper.writeValueAsString(reply);
    assertThat(stored).contains("\"headers\":[{\"name\":\"Location\",\"value\":\"/\"}");
    assertThat(DataType.objectMapper.readValue(stored, Reply.class)).isEqualTo(reply);
  }

  /// A header without a name or value fails to be read, rather than being read as the text "null"
  @Test
  void replyWithAHeaderWithoutValueFails() {
    assertThatThrownBy(() -> DataType.objectMapper.readValue("{\"status\":303,\"headers\":[{\"name\":\"Location\"}],\"body\":\"\"}", Reply.class))
        .hasRootCauseInstanceOf(NullPointerException.class);
  }
}
