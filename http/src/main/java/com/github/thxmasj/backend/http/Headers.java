package com.github.thxmasj.backend.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
/// `a, b` equals one sent twice, as `a` and `b`. The order of fields of different names doesn't matter, as it carries
/// no meaning in HTTP.
public final class Headers {

  private static final Headers none = new Headers(List.of());

  /// A header field: a name and one of its values. The name is a token of RFC 9110 section 5.6.2, which excludes e.g.
  /// the pseudo-headers of HTTP/2, such as `:status`, and the value has no line break, so that the field can be stored
  /// as a line.
  public record Field(String name, String value) {

    public Field {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(value, "value");
      if (!isToken(name))
        throw new IllegalArgumentException("Invalid header name: <" + name + ">");
      if (value.chars().anyMatch(c -> c == '\r' || c == '\n'))
        throw new IllegalArgumentException("Invalid value of header " + name + ": line break");
    }

    /// Whether the field has the name, compared without case
    public boolean is(String name) {
      return this.name.equalsIgnoreCase(name);
    }

    /// Whether the name is a token, of the characters `tchar` of RFC 9110 section 5.6.2, which are ASCII
    public static boolean isToken(String name) {
      return !name.isEmpty()
          && name.chars().allMatch(c -> c < 128 && (Character.isLetterOrDigit(c) || "!#$%&'*+-.^_`|~".indexOf(c) >= 0));
    }
  }

  private final List<Field> fields;
  /// The values of each name, by the name in lower case, joined with `, `, which equality compares. Made when first
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

  /// A header for each entry, in order, e.g. the header lines of a message as Netty received them, where a name may
  /// appear several times
  public static Headers ofEntries(List<? extends Map.Entry<String, String>> entries) {
    var builder = builder();
    entries.forEach(e -> builder.add(e.getKey(), e.getValue()));
    return builder.build();
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
      // Names are ASCII, so lower case is the same in every locale
      var map = new HashMap<String, String>();
      for (Field field : fields)
        map.merge(field.name().toLowerCase(Locale.ROOT), field.value(), (a, b) -> a + ", " + b);
      result = Map.copyOf(map);
      combined = result;
    }
    return result;
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
