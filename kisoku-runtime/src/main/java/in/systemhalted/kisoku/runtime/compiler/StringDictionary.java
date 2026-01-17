package in.systemhalted.kisoku.runtime.compiler;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds and manages a string dictionary for value compression during compilation.
 *
 * <p>Assigns unique 4-byte integer IDs to strings. ID 0 is reserved for null/empty values.
 */
final class StringDictionary {
  private final Map<String, Integer> stringToId = new LinkedHashMap<>();
  private int nextId = 1; // 0 is reserved for null/empty

  /** Reserved ID for null or empty values. */
  static final int NULL_ID = 0;

  /**
   * Adds a string to the dictionary if not already present.
   *
   * @param value the string value (may be null or empty)
   * @return the assigned ID (0 for null/empty, positive integer otherwise)
   */
  int add(String value) {
    if (value == null || value.isEmpty()) {
      return NULL_ID;
    }
    return stringToId.computeIfAbsent(value, k -> nextId++);
  }

  /**
   * Gets the ID for a previously added string.
   *
   * @param value the string value
   * @return the ID, or NULL_ID if not found or null/empty
   */
  int getId(String value) {
    if (value == null || value.isEmpty()) {
      return NULL_ID;
    }
    Integer id = stringToId.get(value);
    return id != null ? id : NULL_ID;
  }

  /** Returns the number of unique non-empty strings in the dictionary. */
  int size() {
    return stringToId.size();
  }

  /**
   * Serializes the dictionary to binary format.
   *
   * <p>Format:
   *
   * <pre>
   * entry_count (4 bytes)
   * Entry 0: length (2 bytes) + UTF-8 bytes
   * Entry 1: length (2 bytes) + UTF-8 bytes
   * ...
   * </pre>
   *
   * @return the serialized dictionary bytes
   */
  byte[] serialize() {
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      DataOutputStream dos = new DataOutputStream(baos);

      dos.writeInt(stringToId.size());

      for (String value : stringToId.keySet()) {
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
