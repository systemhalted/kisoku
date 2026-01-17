package in.systemhalted.kisoku.runtime.compiler;

import in.systemhalted.kisoku.api.ColumnType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Base class for encoding column data in columnar format.
 *
 * <p>Each encoder produces a presence bitmap followed by encoded values.
 */
abstract class ColumnEncoder {
  protected final StringDictionary dictionary;
  protected final ColumnType columnType;

  ColumnEncoder(StringDictionary dictionary, ColumnType columnType) {
    this.dictionary = dictionary;
    this.columnType = columnType;
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
   * Encodes a string value as a dictionary ID.
   *
   * @param value the string value
   * @return dictionary ID (0 for null/empty)
   */
  protected int encodeToDictionaryId(String value) {
    if (value == null || value.isEmpty()) {
      return StringDictionary.NULL_ID;
    }
    return dictionary.getId(value);
  }

  /**
   * Parses an integer value from a string.
   *
   * @param value the string value
   * @return parsed integer, or 0 if null/empty
   */
  protected int parseInteger(String value) {
    if (value == null || value.isEmpty()) {
      return 0;
    }
    return Integer.parseInt(value.trim());
  }

  /**
   * Parses a long value from a string.
   *
   * @param value the string value
   * @return parsed long, or 0 if null/empty
   */
  protected long parseLong(String value) {
    if (value == null || value.isEmpty()) {
      return 0;
    }
    return Long.parseLong(value.trim());
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
