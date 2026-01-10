package in.systemhalted.kisoku.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamingCsvReaderTest {
  @Test
  void readsCommaSeparatedCells(@TempDir Path tempDir) throws IOException {
    Path path = writeCsv(tempDir, "A,B,C");
    try (StreamingCsvReader reader = new StreamingCsvReader(path)) {
      assertArrayEquals(new String[] {"A", "B", "C"}, reader.readNext());
      assertNull(reader.readNext());
    }
  }

  @Test
  void preservesEmptyCells(@TempDir Path tempDir) throws IOException {
    Path path = writeCsv(tempDir, "A,,C", "A,B,");
    try (StreamingCsvReader reader = new StreamingCsvReader(path)) {
      assertArrayEquals(new String[] {"A", "", "C"}, reader.readNext());
      assertArrayEquals(new String[] {"A", "B", ""}, reader.readNext());
    }
  }

  @Test
  void ignoresCommasInsideParentheses(@TempDir Path tempDir) throws IOException {
    Path path = writeCsv(tempDir, "A,(B,C),D");
    try (StreamingCsvReader reader = new StreamingCsvReader(path)) {
      assertArrayEquals(new String[] {"A", "(B,C)", "D"}, reader.readNext());
    }
  }

  @Test
  void tracksRowNumbers(@TempDir Path tempDir) throws IOException {
    Path path = writeCsv(tempDir, "", "A,B", "C,D");
    try (StreamingCsvReader reader = new StreamingCsvReader(path)) {
      assertArrayEquals(new String[] {"A", "B"}, reader.readNext());
      assertEquals(2, reader.rowNumber());
      assertArrayEquals(new String[] {"C", "D"}, reader.readNext());
      assertEquals(3, reader.rowNumber());
    }
  }

  @Test
  void rejectsUnbalancedParentheses(@TempDir Path tempDir) throws IOException {
    Path path = writeCsv(tempDir, "A,(B,C", "A,B),C");
    try (StreamingCsvReader reader = new StreamingCsvReader(path)) {
      IOException first = assertThrows(IOException.class, reader::readNext);
      assertEquals("Unbalanced parentheses at row 1", first.getMessage());
      IOException second = assertThrows(IOException.class, reader::readNext);
      assertEquals("Unbalanced parentheses at row 2", second.getMessage());
    }
  }

  private static Path writeCsv(Path tempDir, String... lines) throws IOException {
    Path path = tempDir.resolve("sample.csv");
    Files.write(path, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    return path;
  }
}
