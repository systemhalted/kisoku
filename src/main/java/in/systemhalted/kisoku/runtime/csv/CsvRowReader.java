package in.systemhalted.kisoku.runtime.csv;

import java.io.IOException;

/**
 * Streaming CSV row reader that preserves empty cells and row ordering.
 */
public interface CsvRowReader extends AutoCloseable {
  /**
   * Reads the next row or returns null on EOF.
   */
  String[] readNext() throws IOException;

  /**
   * Returns the 1-based physical row number of the last row read.
   */
  long rowNumber();

  @Override
  void close() throws IOException;
}
