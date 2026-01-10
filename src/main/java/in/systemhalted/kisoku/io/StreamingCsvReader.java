package in.systemhalted.kisoku.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class StreamingCsvReader implements AutoCloseable {
  private final BufferedReader reader;
  private long rowNumber;

  public StreamingCsvReader(Path path) throws IOException {
    this.reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
  }

  public String[] readNext() throws IOException {
    String line;
    while ((line = reader.readLine()) != null) {
      rowNumber++;
      if (line.isEmpty()) {
        // skip empty lines
        continue;
      }

      List<String> cells = new ArrayList<>();
      StringBuilder cell = new StringBuilder();
      int depth = 0;

      for (int i = 0; i < line.length(); i++) {
        char ch = line.charAt(i);
        if (ch == '(') {
          depth++;
          cell.append(ch);
        } else if (ch == ')') {
          depth--;
          if (depth < 0) {
            throw new IOException("Unbalanced parentheses at row " + rowNumber);
          }
          cell.append(ch);
        } else if (ch == ',' && depth == 0) {
          cells.add(cell.toString());
          cell.setLength(0);
        } else {
          cell.append(ch);
        }
      }

      if (depth != 0) {
        throw new IOException("Unbalanced parentheses at row " + rowNumber);
      }
      cells.add(cell.toString());

      return cells.toArray(new String[0]);
    }
    return null;
  }

  public long rowNumber() {
    return rowNumber;
  }

  @Override
  public void close() throws IOException {
    reader.close();
  }
}
