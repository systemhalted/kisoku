package in.systemhalted.kisoku.runtime.compiler;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.compilation.CompilationException;
import in.systemhalted.kisoku.runtime.codec.ComparableCodes;
import in.systemhalted.kisoku.runtime.codec.ValueCodec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Base class for encoding column data in columnar format.
 *
 * <p>Each encoder produces a presence bitmap followed by encoded values. Every cell is reduced to
 * the order-preserving {@code long} domain of {@link ComparableCodes}, so a stored code can be
 * compared directly against a coerced input code by any operator, ordering operators included.
 */
abstract class ColumnEncoder {
  protected final StringDictionary dictionary;
  protected final ColumnType columnType;

  /** Decimal scale for DECIMAL columns; 0 for every other type. */
  protected final int scale;

  ColumnEncoder(StringDictionary dictionary, ColumnType columnType, int scale) {
    this.dictionary = dictionary;
    this.columnType = columnType;
    this.scale = scale;
  }

  /**
   * Encodes a column's data from all rows.
   *
   * @param rows list of parsed row data (String arrays)
   * @param columnIndex the column index to encode
   * @return encoded column bytes
   */
  abstract byte[] encode(List<String[]> rows, int columnIndex);

  /**
   * Builds a presence bitmap indicating which rows have non-blank values.
   *
   * @param rows list of parsed row data
   * @param columnIndex the column index
   * @return presence bitmap bytes (ceil(rowCount/8) bytes)
   */
  protected byte[] buildPresenceBitmap(List<String[]> rows, int columnIndex) {
    int rowCount = rows.size();
    int bitmapSize = (rowCount + 7) / 8;
    byte[] bitmap = new byte[bitmapSize];

    for (int i = 0; i < rowCount; i++) {
      String value = rows.get(i)[columnIndex];
      if (value != null && !value.isEmpty()) {
        int byteIndex = i / 8;
        int bitIndex = 7 - (i % 8); // MSB-first
        bitmap[byteIndex] |= (1 << bitIndex);
      }
    }

    return bitmap;
  }

  /**
   * Checks if a cell value is present (non-blank).
   *
   * @param value the cell value
   * @return true if present
   */
  protected boolean isPresent(String value) {
    return value != null && !value.isEmpty();
  }

  /**
   * Creates a DataOutputStream wrapping a ByteArrayOutputStream.
   *
   * @param baos the underlying byte array output stream
   * @return a data output stream for writing binary data
   */
  protected DataOutputStream createDataOutputStream(ByteArrayOutputStream baos) {
    return new DataOutputStream(baos);
  }

  /**
   * Encodes one cell operand into its order-preserving code.
   *
   * @param value the cell text, or null/empty for a blank cell
   * @return the code, or {@link ComparableCodes#NULL_CODE} for a blank cell
   */
  protected long encodeCell(String value) {
    if (!isPresent(value)) {
      return ComparableCodes.NULL_CODE;
    }
    String text = value.trim();
    try {
      return switch (columnType) {
        case STRING -> dictionary.codeFor(text);
        case INTEGER -> ValueCodec.encodeInteger(Long.parseLong(text));
        case DECIMAL -> ValueCodec.encodeDecimal(new BigDecimal(text), scale);
        case BOOLEAN -> ValueCodec.encodeBoolean(parseBoolean(text));
        case DATE -> ValueCodec.encodeDate(LocalDate.parse(text));
        case TIMESTAMP -> ValueCodec.encodeTimestamp(parseTimestamp(text));
      };
    } catch (NumberFormatException | DateTimeParseException e) {
      throw new CompilationException(
          "Value '" + text + "' is not a valid " + columnType + ": " + e.getMessage(), e);
    }
  }

  private boolean parseBoolean(String value) {
    String lower = value.toLowerCase();
    return "true".equals(lower) || "1".equals(lower) || "yes".equals(lower);
  }

  private Instant parseTimestamp(String value) {
    Instant instant = Instant.parse(value);
    if (instant.getNano() % 1000 != 0) {
      throw new CompilationException(
          "Timestamp '"
              + value
              + "' has sub-microsecond precision, which the artifact cannot store");
    }
    return instant;
  }

  /**
   * Writes bytes safely, wrapping IOException.
   *
   * @param dos the data output stream
   * @param action the write action
   */
  protected void writeOrThrow(DataOutputStream dos, WriteAction action) {
    try {
      action.write(dos);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to encode column data", e);
    }
  }

  @FunctionalInterface
  protected interface WriteAction {
    void write(DataOutputStream dos) throws IOException;
  }
}
