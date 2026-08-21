package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.runtime.codec.ComparableCodes;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Read-only access to the string dictionary for value lookup during evaluation.
 *
 * <p>Dictionary format:
 *
 * <pre>
 * entry_count (4 bytes)
 * Entry 1: length (2 bytes) + UTF-8 bytes   // sorted ascending
 * Entry 2: length (2 bytes) + UTF-8 bytes
 * ...
 * </pre>
 *
 * <p>Entries are written in sorted order, so an entry's 1-based ID is also its rank. Lookups are a
 * binary search over that ordering rather than a hash map, which keeps a second copy of every
 * string off the heap.
 *
 * <p>ID 0 is reserved for null/empty values.
 */
final class StringDictionaryReader {
  static final int NULL_ID = 0;

  /** Sorted entries; index 0 is unused so IDs are 1-based. */
  private final String[] strings;

  private StringDictionaryReader(String[] strings) {
    this.strings = strings;
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
   * @return the ID, or NULL_ID if not found or null/empty
   */
  int getId(String value) {
    if (value == null || value.isEmpty()) {
      return NULL_ID;
    }
    int found = binarySearch(value);
    return found > 0 ? found : NULL_ID;
  }

  /**
   * Gets the order-preserving code for a string input.
   *
   * <p>A value present in the dictionary yields the same code the compiler stored. A value the
   * compiler never saw yields a code positioned strictly between its two neighbouring entries, so
   * it compares correctly under ordering operators while equalling no stored value.
   *
   * @param value the string value
   * @return the order-preserving code
   */
  long codeFor(String value) {
    if (value == null || value.isEmpty()) {
      return ComparableCodes.NULL_CODE;
    }
    int found = binarySearch(value);
    if (found > 0) {
      return ComparableCodes.exact(found);
    }
    // Not present: -(insertion point) - 1, where the insertion point is the 1-based index of the
    // first entry greater than the value, so (insertionPoint - 1) is the greatest entry below it.
    int insertionPoint = -found - 1;
    return ComparableCodes.between(insertionPoint - 1);
  }

  /** Returns the number of entries in the dictionary (excluding null entry). */
  int size() {
    return strings.length - 1;
  }

  /** Binary search over entries 1..n; mirrors {@link Arrays#binarySearch} return conventions. */
  private int binarySearch(String value) {
    return Arrays.binarySearch(strings, 1, strings.length, value);
  }
}
