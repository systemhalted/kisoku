package in.systemhalted.kisoku.runtime.csv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StreamingCsvRowReaderTest {

  @Test
  void readsCommaSeparatedCells() throws IOException {
    try (StreamingCsvRowReader reader = readerFor("A,B,C")) {
      assertArrayEquals(new String[] {"A", "B", "C"}, reader.readNext());
      assertNull(reader.readNext());
    }
  }

  @Test
  void trimsCellsAndSkipsWhitespaceLines() throws IOException {
    try (StreamingCsvRowReader reader = readerFor("   ", " A , (B, C) ,  D  ")) {
      assertArrayEquals(new String[] {"A", "(B, C)", "D"}, reader.readNext());
      assertEquals(2, reader.rowNumber());
    }
  }

  @Test
  void rejectsUnbalancedParentheses() throws IOException {
    try (StreamingCsvRowReader reader = readerFor("A,(B,C")) {
      IOException error = assertThrows(IOException.class, reader::readNext);
      assertEquals("Unbalanced parentheses at row 1", error.getMessage());
    }
  }

  private static StreamingCsvRowReader readerFor(String... lines) {
    String data = String.join("\n", lines);
    return new StreamingCsvRowReader(
        new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
  }
}
