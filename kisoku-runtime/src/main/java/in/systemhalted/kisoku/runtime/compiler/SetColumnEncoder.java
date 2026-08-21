package in.systemhalted.kisoku.runtime.compiler;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.compilation.CompilationException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes set column data (multiple values per row).
 *
 * <p>Handles operators: IN, NOT_IN
 *
 * <p>Format:
 *
 * <pre>
 * presence_bitmap (ceil(row_count/8) bytes)
 * list_offsets[row_count] (4 bytes each)
 * list_lengths[row_count] (2 bytes each)
 * all_values[] (8 bytes each)
 * </pre>
 */
final class SetColumnEncoder extends ColumnEncoder {

  /** Widest set a row can hold, bounded by the 16-bit length field. */
  private static final int MAX_SET_SIZE = 0xFFFF;

  SetColumnEncoder(StringDictionary dictionary, ColumnType columnType, int scale) {
    super(dictionary, columnType, scale);
  }

  @Override
  byte[] encode(List<String[]> rows, int columnIndex) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = createDataOutputStream(baos);

    // Write presence bitmap
    byte[] bitmap = buildPresenceBitmap(rows, columnIndex);
    writeOrThrow(dos, d -> d.write(bitmap));

    // Parse all sets and collect values
    List<long[]> parsedSets = new ArrayList<>(rows.size());
    for (String[] row : rows) {
      parsedSets.add(parseSet(row[columnIndex]));
    }

    // Calculate offsets and build all_values array
    int[] offsets = new int[rows.size()];
    short[] lengths = new short[rows.size()];
    List<Long> allValues = new ArrayList<>();
    int currentOffset = 0;

    for (int i = 0; i < parsedSets.size(); i++) {
      long[] setValues = parsedSets.get(i);
      offsets[i] = currentOffset;
      lengths[i] = (short) setValues.length;
      for (long v : setValues) {
        allValues.add(v);
      }
      currentOffset += setValues.length;
    }

    // Write list_offsets
    for (int offset : offsets) {
      writeOrThrow(dos, d -> d.writeInt(offset));
    }

    // Write list_lengths
    for (short length : lengths) {
      writeOrThrow(dos, d -> d.writeShort(length));
    }

    // Write all_values
    for (long v : allValues) {
      writeOrThrow(dos, d -> d.writeLong(v));
    }

    return baos.toByteArray();
  }

  /**
   * Parses a set value in (a,b,c) format.
   *
   * @param value the cell value, e.g., "(APAC,EMEA)"
   * @return the encoded member codes, or an empty array if blank
   */
  private long[] parseSet(String value) {
    if (!isPresent(value)) {
      return new long[0];
    }

    String trimmed = value.trim();
    if (!trimmed.startsWith("(") || !trimmed.endsWith(")")) {
      throw new CompilationException("Set must be in (a,b,c) format: " + value);
    }

    String inner = trimmed.substring(1, trimmed.length() - 1);
    if (inner.isEmpty()) {
      return new long[0];
    }

    String[] parts = inner.split(",");
    if (parts.length > MAX_SET_SIZE) {
      throw new CompilationException(
          "Set has " + parts.length + " members, which exceeds the limit of " + MAX_SET_SIZE);
    }
    long[] result = new long[parts.length];
    for (int i = 0; i < parts.length; i++) {
      result[i] = encodeCell(parts[i]);
    }
    return result;
  }
}
