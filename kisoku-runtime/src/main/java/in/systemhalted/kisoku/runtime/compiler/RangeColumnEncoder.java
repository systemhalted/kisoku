package in.systemhalted.kisoku.runtime.compiler;

import in.systemhalted.kisoku.api.ColumnType;
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
 * min_values[row_count] (4 bytes each)
 * max_values[row_count] (4 bytes each)
 * </pre>
 */
final class RangeColumnEncoder extends ColumnEncoder {

  RangeColumnEncoder(StringDictionary dictionary, ColumnType columnType) {
    super(dictionary, columnType);
  }

  @Override
  byte[] encode(List<String[]> rows, int columnIndex) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = createDataOutputStream(baos);

    // Write presence bitmap
    byte[] bitmap = buildPresenceBitmap(rows, columnIndex);
    writeOrThrow(dos, d -> d.write(bitmap));

    // Parse all ranges first
    int[][] ranges = new int[rows.size()][2];
    for (int i = 0; i < rows.size(); i++) {
      String value = rows.get(i)[columnIndex];
      ranges[i] = parseRange(value);
    }

    // Write min values
    for (int[] range : ranges) {
      int min = range[0];
      writeOrThrow(dos, d -> d.writeInt(min));
    }

    // Write max values
    for (int[] range : ranges) {
      int max = range[1];
      writeOrThrow(dos, d -> d.writeInt(max));
    }

    return baos.toByteArray();
  }

  /**
   * Parses a range value in (min,max) format.
   *
   * @param value the cell value, e.g., "(18,29)"
   * @return int array with [min, max], or [0, 0] if empty
   */
  private int[] parseRange(String value) {
    if (!isPresent(value)) {
      return new int[] {0, 0};
    }

    String trimmed = value.trim();
    if (!trimmed.startsWith("(") || !trimmed.endsWith(")")) {
      throw new IllegalArgumentException("Range must be in (min,max) format: " + value);
    }

    String inner = trimmed.substring(1, trimmed.length() - 1);
    String[] parts = inner.split(",", 2);
    if (parts.length != 2) {
      throw new IllegalArgumentException("Range must have exactly two parts: " + value);
    }

    int min = encodeRangeValue(parts[0].trim());
    int max = encodeRangeValue(parts[1].trim());
    return new int[] {min, max};
  }

  private int encodeRangeValue(String value) {
    return switch (columnType) {
      case STRING -> dictionary.getId(value);
      case INTEGER -> parseInteger(value);
      case DECIMAL -> dictionary.getId(value);
      case DATE -> encodeDateAsDays(value);
      default -> throw new IllegalArgumentException("Unsupported range type: " + columnType);
    };
  }

  private int encodeDateAsDays(String value) {
    if (value == null || value.isEmpty()) {
      return 0;
    }
    try {
      java.time.LocalDate date = java.time.LocalDate.parse(value.trim());
      return (int) date.toEpochDay();
    } catch (java.time.format.DateTimeParseException e) {
      throw new IllegalArgumentException("Invalid date format: " + value, e);
    }
  }
}
