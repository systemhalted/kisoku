package in.systemhalted.kisoku.runtime.csv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * CSV row reader that streams from an input stream.
 *
 * <p>TODO: implement comma-splitting that ignores commas inside parentheses
 * to support operand formats like "(A,B)" without quotes.
 */
public final class StreamingCsvRowReader implements CsvRowReader {
  private final BufferedReader reader;
  private long rowNumber;

  public StreamingCsvRowReader(InputStream inputStream) {
    this.reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
  }

  @Override
  public String[] readNext() throws IOException {
    // TODO: read a line, split on commas only when not inside parentheses.
    throw new UnsupportedOperationException("TODO: implement streaming CSV parsing.");
  }

  @Override
  public long rowNumber() {
    return rowNumber;
  }

  @Override
  public void close() throws IOException {
    reader.close();
  }
}
