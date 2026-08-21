package in.systemhalted.kisoku.runtime.compiler;

import in.systemhalted.kisoku.runtime.codec.ComparableCodes;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Builds the string dictionary used to encode string-valued cells during compilation.
 *
 * <p>Entries are assigned IDs in <em>sorted</em> order once collection finishes, so a string's ID
 * ranks it against every other string in the table. That is what makes {@code GT}/{@code LT} and
 * the range operators meaningful on STRING columns, and it lets the loader resolve an input by
 * binary search instead of keeping a hash map of every value on the heap.
 *
 * <p>IDs are 1-based; 0 is reserved for null/empty. Callers encode cells through {@link
 * #codeFor(String)}, which returns the order-preserving code, not the raw ID.
 */
final class StringDictionary {
  /** Reserved ID for null or empty values. */
  static final int NULL_ID = 0;

  private final TreeSet<String> values = new TreeSet<>();

  /** Sorted entries, index 0 unused so IDs are 1-based. Null until {@link #seal()}. */
  private String[] sorted;

  private Map<String, Integer> idByValue;

  /**
   * Adds a string to the dictionary if not already present.
   *
   * @param value the string value (may be null or empty)
   * @throws IllegalStateException if the dictionary has already been sealed
   */
  void add(String value) {
    if (value == null || value.isEmpty()) {
      return;
    }
    if (sorted != null) {
      throw new IllegalStateException("Cannot add to a sealed dictionary: " + value);
    }
    values.add(value);
  }

  /**
   * Freezes the dictionary and assigns IDs in sorted order. Called automatically by the first
   * lookup; adding afterwards is a programming error.
   */
  private void seal() {
    if (sorted != null) {
      return;
    }
    sorted = new String[values.size() + 1];
    idByValue = new HashMap<>(values.size() * 2);
    int id = 1;
    for (String value : values) {
      sorted[id] = value;
      idByValue.put(value, id);
      id++;
    }
  }

  /**
   * Gets the ID for a previously added string.
   *
   * @param value the string value
   * @return the 1-based sorted ID, or NULL_ID if not found or null/empty
   */
  int getId(String value) {
    if (value == null || value.isEmpty()) {
      return NULL_ID;
    }
    seal();
    Integer id = idByValue.get(value);
    return id != null ? id : NULL_ID;
  }

  /**
   * Gets the order-preserving code for a string cell.
   *
   * @param value the string value
   * @return {@link ComparableCodes#NULL_CODE} for null/empty, otherwise the code for its sorted ID
   */
  long codeFor(String value) {
    int id = getId(value);
    return id == NULL_ID ? ComparableCodes.NULL_CODE : ComparableCodes.exact(id);
  }

  /** Returns the number of unique non-empty strings in the dictionary. */
  int size() {
    seal();
    return sorted.length - 1;
  }

  /**
   * Serializes the dictionary to binary format.
   *
   * <p>Format:
   *
   * <pre>
   * entry_count (4 bytes)
   * Entry 1: length (2 bytes) + UTF-8 bytes   // sorted ascending
   * Entry 2: length (2 bytes) + UTF-8 bytes
   * ...
   * </pre>
   *
   * @return the serialized dictionary bytes
   */
  byte[] serialize() {
    seal();
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      DataOutputStream dos = new DataOutputStream(baos);

      dos.writeInt(sorted.length - 1);

      for (String value : Arrays.asList(sorted).subList(1, sorted.length)) {
        byte[] utf8Bytes = value.getBytes(StandardCharsets.UTF_8);
        if (utf8Bytes.length > 65535) {
          throw new IllegalStateException("String too long for dictionary: " + utf8Bytes.length);
        }
        dos.writeShort(utf8Bytes.length);
        dos.write(utf8Bytes);
      }

      dos.flush();
      return baos.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to serialize dictionary", e);
    }
  }
}
