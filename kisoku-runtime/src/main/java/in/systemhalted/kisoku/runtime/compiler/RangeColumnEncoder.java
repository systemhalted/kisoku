package in.systemhalted.kisoku.runtime.compiler;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.compilation.CompilationException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

/**
 * Encodes range column data (min and max values per row).
 *
 * <p>Handles operators: BETWEEN_INCLUSIVE, BETWEEN_EXCLUSIVE, NOT_BETWEEN_INCLUSIVE,
 * NOT_BETWEEN_EXCLUSIVE
 *
 * <p>Format:
 *
 * <pre>
 * presence_bitmap (ceil(row_count/8) bytes)
 * min_values[row_count] (8 bytes each)
 * max_values[row_count] (8 bytes each)
 * </pre>
 */
final class RangeColumnEncoder extends ColumnEncoder {

  RangeColumnEncoder(StringDictionary dictionary, ColumnType columnType, int scale) {
    super(dictionary, columnType, scale);
  }

  @Override
  byte[] encode(List<String[]> rows, int columnIndex) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = createDataOutputStream(baos);

    // Write presence bitmap
    byte[] bitmap = buildPresenceBitmap(rows, columnIndex);
    writeOrThrow(dos, d -> d.write(bitmap));

    // Parse all ranges first
    long[][] ranges = new long[rows.size()][2];
    for (int i = 0; i < rows.size(); i++) {
      ranges[i] = parseRange(rows.get(i)[columnIndex]);
    }

    // Write min values
    for (long[] range : ranges) {
      long min = range[0];
      writeOrThrow(dos, d -> d.writeLong(min));
    }

    // Write max values
    for (long[] range : ranges) {
      long max = range[1];
      writeOrThrow(dos, d -> d.writeLong(max));
    }

    return baos.toByteArray();
  }

  /**
   * Parses a range value in (min,max) format.
   *
   * @param value the cell value, e.g., "(18,29)"
   * @return two codes, [min, max], or [0, 0] if blank
   */
  private long[] parseRange(String value) {
    if (!isPresent(value)) {
      return new long[] {0L, 0L};
    }

    String trimmed = value.trim();
    if (!trimmed.startsWith("(") || !trimmed.endsWith(")")) {
      throw new CompilationException("Range must be in (min,max) format: " + value);
    }

    String inner = trimmed.substring(1, trimmed.length() - 1);
    String[] parts = inner.split(",", 2);
    if (parts.length != 2) {
      throw new CompilationException("Range must have exactly two parts: " + value);
    }

    return new long[] {encodeCell(parts[0]), encodeCell(parts[1])};
  }
}
