package in.systemhalted.kisoku.runtime.compiler;

import in.systemhalted.kisoku.api.ColumnType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

/**
 * Encodes scalar column data (single value per row).
 *
 * <p>Handles operators: RULE_ID, PRIORITY, SET, EQ, NE, GT, GTE, LT, LTE
 *
 * <p>Format:
 *
 * <pre>
 * presence_bitmap (ceil(row_count/8) bytes)
 * values[row_count] (4 bytes each)
 * </pre>
 */
final class ScalarColumnEncoder extends ColumnEncoder {

  ScalarColumnEncoder(StringDictionary dictionary, ColumnType columnType) {
    super(dictionary, columnType);
  }

  @Override
  byte[] encode(List<String[]> rows, int columnIndex) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = createDataOutputStream(baos);

    // Write presence bitmap
    byte[] bitmap = buildPresenceBitmap(rows, columnIndex);
    writeOrThrow(dos, d -> d.write(bitmap));

    // Write values
    for (String[] row : rows) {
      String value = row[columnIndex];
      int encoded = encodeValue(value);
      writeOrThrow(dos, d -> d.writeInt(encoded));
    }

    return baos.toByteArray();
  }

  private int encodeValue(String value) {
    if (!isPresent(value)) {
      return 0;
    }

    return switch (columnType) {
      case STRING -> encodeToDictionaryId(value);
      case INTEGER -> parseInteger(value);
      case DECIMAL -> encodeToDictionaryId(value); // Store as string to preserve precision
      case BOOLEAN -> parseBoolean(value) ? 1 : 0;
      case DATE -> encodeDateAsDays(value);
      case TIMESTAMP -> encodeTimestampAsInt(value);
    };
  }

  private boolean parseBoolean(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    String lower = value.trim().toLowerCase();
    return "true".equals(lower) || "1".equals(lower) || "yes".equals(lower);
  }

  private int encodeDateAsDays(String value) {
    if (value == null || value.isEmpty()) {
      return 0;
    }
    // Parse ISO-8601 date (YYYY-MM-DD) and convert to days since epoch
    try {
      java.time.LocalDate date = java.time.LocalDate.parse(value.trim());
      return (int) date.toEpochDay();
    } catch (java.time.format.DateTimeParseException e) {
      throw new IllegalArgumentException("Invalid date format: " + value, e);
    }
  }

  private int encodeTimestampAsInt(String value) {
    if (value == null || value.isEmpty()) {
      return 0;
    }
    // For timestamps, we store dictionary ID since millis don't fit in 4 bytes
    // The loader will parse the string back to Instant
    return encodeToDictionaryId(value);
  }
}
