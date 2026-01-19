package in.systemhalted.kisoku.runtime.loader;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Read-only access to the string dictionary for value lookup during evaluation.
 *
 * <p>Dictionary format:
 *
 * <pre>
 * entry_count (4 bytes)
 * Entry 1: length (2 bytes) + UTF-8 bytes
 * Entry 2: length (2 bytes) + UTF-8 bytes
 * ...
 * </pre>
 *
 * ID 0 is reserved for null/empty values.
 */
final class StringDictionaryReader {
  static final int NULL_ID = 0;

  private final String[] strings;
  private final Map<String, Integer> reverseMap;

  private StringDictionaryReader(String[] strings) {
    this.strings = strings;
    this.reverseMap = new HashMap<>();
    for (int i = 1; i < strings.length; i++) {
      if (strings[i] != null) {
        reverseMap.put(strings[i], i);
      }
    }
  }

  /**
   * Reads the string dictionary from the buffer at the specified offset.
   *
   * @param buffer the ByteBuffer containing the artifact
   * @param offset the offset of the dictionary section
   * @return a StringDictionaryReader
   */
  static StringDictionaryReader read(ByteBuffer buffer, int offset) {
    buffer.position(offset);
    int entryCount = buffer.getInt();

    // Index 0 is reserved for null, so array size is entryCount + 1
    String[] strings = new String[entryCount + 1];
    strings[0] = null;

    for (int i = 1; i <= entryCount; i++) {
      int length = buffer.getShort() & 0xFFFF;
      byte[] bytes = new byte[length];
      buffer.get(bytes);
      strings[i] = new String(bytes, StandardCharsets.UTF_8);
    }

    return new StringDictionaryReader(strings);
  }

  /**
   * Gets the string for the given ID.
   *
   * @param id the dictionary ID
   * @return the string, or null if id is 0 or invalid
   */
  String get(int id) {
    if (id <= 0 || id >= strings.length) {
      return null;
    }
    return strings[id];
  }

  /**
   * Gets the ID for a string value (for input comparison).
   *
   * @param value the string value to look up
   * @return the ID, or NULL_ID if not found
   */
  int getId(String value) {
    if (value == null || value.isEmpty()) {
      return NULL_ID;
    }
    Integer id = reverseMap.get(value);
    return id != null ? id : NULL_ID;
  }

  /** Returns the number of entries in the dictionary (excluding null entry). */
  int size() {
    return strings.length - 1;
  }
}
