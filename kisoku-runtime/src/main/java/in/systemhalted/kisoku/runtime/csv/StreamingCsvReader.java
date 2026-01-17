package in.systemhalted.kisoku.runtime.csv;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * File-based CSV reader that delegates to {@link StreamingCsvRowReader}.
 *
 * <p>This class provides a convenient way to read CSV files from the filesystem while sharing the
 * parsing logic with the stream-based reader.
 */
public final class StreamingCsvReader implements AutoCloseable {
  private final InputStream inputStream;
  private final CsvRowReader delegate;

  public StreamingCsvReader(Path path) throws IOException {
    this.inputStream = Files.newInputStream(path);
    this.delegate = new StreamingCsvRowReader(inputStream);
  }

  public String[] readNext() throws IOException {
    return delegate.readNext();
  }

  public long rowNumber() {
    return delegate.rowNumber();
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }
}
