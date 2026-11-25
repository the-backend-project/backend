package com.github.thxmasj.backend.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/// The headers of an HTTP message: fields of a name and a value, in the order they were sent or received, where a name
/// may have several values (RFC 9110 section 5). Names are looked up without case, and keep the case they were written
/// in. Headers are immutable: [#with(String, String)], [#plus(String, String)] and [#without(String)] return new
/// headers, and a [Builder] makes them a field at a time.
///
/// Headers are equal if they have the same values for each name, with the names compared without case, and the values
/// of a name joined in order with `, `, as a recipient may join them (RFC 9110 section 5.3): a header sent once as
/// `a, b` equals one sent twice, as `a` and `b`. The values of `Set-Cookie`, which can't be joined, are compared apart. The order of fields of different names doesn't matter, as it carries
/// no meaning in HTTP.
public final class Headers {

  private static final Headers none = new Headers(List.of());

  /// A header field: a name and one of its values, which can be stored as a line `name:value` and read back as they
  /// were: the name is not blank, has no colon, which excludes e.g. the pseudo-headers of HTTP/2, such as `:status`, no
  /// line break and no whitespace around it, and the value has no line break. The names of the headers received are
  /// tokens of RFC 9110 section 5.6.2, which the HTTP servers and clients check, but a name made by an application or
  /// stored by an earlier version need not be.
  public record Field(String name, String value) {

    public Field {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(value, "value");
      if (name.isEmpty() || !name.strip().equals(name) || name.chars().anyMatch(c -> c == ':' || c == '\r' || c == '\n'))
        throw new IllegalArgumentException("Invalid header name: <" + name + ">");
      if (value.chars().anyMatch(c -> c == '\r' || c == '\n'))
        throw new IllegalArgumentException("Invalid value of header " + name + ": line break");
    }

    /// Whether the field has the name, compared without case
    public boolean is(String name) {
      return this.name.equalsIgnoreCase(name);
    }
  }

  private final List<Field> fields;
  /// The values of each name, by the name [#folded(String)], joined with `, `, which equality compares. Made when first
  /// needed, and immutable, so made again at most when used by several threads at once.
  private Map<String, String> combined;

  private Headers(List<Field> fields) {
    this.fields = fields;
  }

  /// No headers
  public static Headers none() {
    return none;
  }

  /// A header for each entry of the map, with one value per name, in the order of the map
  public static Headers of(Map<String, String> headers) {
    Objects.requireNonNull(headers, "headers, which are Map.of() for none");
    var builder = builder();
    headers.forEach(builder::add);
    return builder.build();
  }

  /// The headers of a request received, a field for each entry, in order, where a name may appear several times
  ///
  /// @throws IllegalArgumentException if a field is invalid (RFC 9110 section 5.5 and RFC 9113 section 8.2.1): its
  ///                                  name isn't a token, or its value has a CR, LF or NUL, or starts or ends with
  ///                                  whitespace, which the server answers as a bad request
  public static Headers ofRequestEntries(List<? extends Map.Entry<String, String>> entries) {
    var builder = builder();
    for (var entry : entries) {
      String name = entry.getKey();
      String value = entry.getValue();
      if (!isToken(name))
        throw new IllegalArgumentException("Invalid header name: <" + name + ">");
      if (value.chars().anyMatch(c -> c == '\r' || c == '\n' || c == 0) || !value.equals(value.strip()))
        throw new IllegalArgumentException("Invalid value of header " + name);
      builder.add(name, value);
    }
    return builder.build();
  }

  /// The headers of a response received, a field for each entry, in order, where a name may appear several times. A
  /// CR, LF or NUL in a value is replaced by a space, as RFC 9110 section 5.5 lets a recipient do, and whitespace around
  /// it is removed, as it is when the response is read back from the event log. The response isn't failed, as the
  /// request it answers may have taken effect.
  public static Headers ofResponseEntries(List<? extends Map.Entry<String, String>> entries) {
    var builder = builder();
    entries.forEach(e -> builder.add(e.getKey(), received(e.getValue())));
    return builder.build();
  }

  /// The value received, with a CR, LF or NUL replaced by a space and the whitespace around it removed
  static String received(String value) {
    return value.replace('\r', ' ').replace('\n', ' ').replace('\0', ' ').strip();
  }

  /// Whether the name is a token, of the characters `tchar` of RFC 9110 section 5.6.2, as the name of a header
  /// received must be
  public static boolean isToken(String name) {
    return !name.isEmpty()
        && name.chars().allMatch(c -> c < 128 && (Character.isLetterOrDigit(c) || "!#$%&'*+-.^_`|~".indexOf(c) >= 0));
  }

  /// The header with the value
  public static Headers of(String name, String value) {
    return builder().add(name, value).build();
  }

  /// The fields, in order
  public static Headers of(List<Field> fields) {
    return fields.isEmpty() ? none : new Headers(List.copyOf(fields));
  }

  public static Builder builder() {
    return new Builder();
  }

  /// The first value of the header, or empty if there is none
  public Optional<String> firstValue(String name) {
    return fields.stream().filter(f -> f.is(name)).map(Field::value).findFirst();
  }

  /// The values of the header, in order, or none if there is none
  public List<String> allValues(String name) {
    return fields.stream().filter(f -> f.is(name)).map(Field::value).toList();
  }

  /// Whether there is a header with the name
  public boolean contains(String name) {
    return fields.stream().anyMatch(f -> f.is(name));
  }

  /// The names, as first written, in the order they first appear
  public Set<String> names() {
    var seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    var ordered = new LinkedHashSet<String>();
    for (Field field : fields)
      if (seen.add(field.name()))
        ordered.add(field.name());
    return Collections.unmodifiableSet(ordered);
  }

  public boolean isEmpty() {
    return fields.isEmpty();
  }

  /// The headers with the name having only the value: it replaces the first value and the others are removed, or it is
  /// added at the end if there is none
  public Headers with(String name, String value) {
    return builder(this).set(name, value).build();
  }

  /// The headers with the value added for the name, after any it has already
  public Headers plus(String name, String value) {
    return builder(this).add(name, value).build();
  }

  /// The headers without any values for the name
  public Headers without(String name) {
    return builder(this).remove(name).build();
  }

  /// The fields, in order
  public List<Field> fields() {
    return fields;
  }

  public Stream<Field> stream() {
    return fields.stream();
  }

  /// Calls the action with the name and value of each field, in order
  public void forEach(BiConsumer<String, String> action) {
    fields.forEach(f -> action.accept(f.name(), f.value()));
  }

  @Override
  public boolean equals(Object o) {
    return o == this || o instanceof Headers that && combined().equals(that.combined());
  }

  @Override
  public int hashCode() {
    return combined().hashCode();
  }

  @Override
  public String toString() {
    return fields.toString();
  }

  private Map<String, String> combined() {
    Map<String, String> result = combined;
    if (result == null) {
      var map = new HashMap<String, String>();
      for (Field field : fields) {
        String name = folded(field.name());
        // Set-Cookie can't be joined, as a cookie may have a comma, e.g. in its Expires (RFC 9110 section 5.3), so its
        // values are compared apart, joined by a line break, which a value can't have
        String separator = name.equals("set-cookie") ? "\n" : ", ";
        map.merge(name, field.value(), (a, b) -> a + separator + b);
      }
      result = Map.copyOf(map);
      combined = result;
    }
    return result;
  }

  /// The name with its case folded a character at a time, as [String#equalsIgnoreCase(String)] compares names, so
  /// that names equal without case have the same folded name, also when they aren't ASCII
  private static String folded(String name) {
    var folded = new StringBuilder(name.length());
    for (int i = 0; i < name.length(); i++)
      folded.append(Character.toLowerCase(Character.toUpperCase(name.charAt(i))));
    return folded.toString();
  }

  private static Builder builder(Headers headers) {
    var builder = new Builder();
    builder.fields.addAll(headers.fields);
    return builder;
  }

  /// Makes headers a field at a time, in the order they are added
  public static final class Builder {

    private final List<Field> fields = new ArrayList<>();

    private Builder() {}

    /// Adds a value for the name, after any it has already
    public Builder add(String name, String value) {
      fields.add(new Field(name, value));
      return this;
    }

    /// Adds the fields of the headers
    public Builder add(Headers headers) {
      fields.addAll(headers.fields);
      return this;
    }

    /// Sets the value of the name: it replaces the first value and the others are removed, or it is added at the end
    /// if there is none
    public Builder set(String name, String value) {
      var field = new Field(name, value);
      int index = indexOf(name);
      if (index == -1) {
        fields.add(field);
      } else {
        fields.set(index, field);
        for (int i = fields.size() - 1; i > index; i--)
          if (fields.get(i).is(name))
            fields.remove(i);
      }
      return this;
    }

    /// Removes the values of the name
    public Builder remove(String name) {
      fields.removeIf(f -> f.is(name));
      return this;
    }

    public Headers build() {
      return Headers.of(fields);
    }

    private int indexOf(String name) {
      for (int i = 0; i < fields.size(); i++)
        if (fields.get(i).is(name))
          return i;
      return -1;
    }
  }
}
