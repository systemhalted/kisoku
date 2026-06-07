package in.systemhalted.kisoku.runtime.loader;

import static org.junit.jupiter.api.Assertions.*;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that the compiler writes real per-column {@code dataOffset}s into column definitions
 * (previously every column was written with offset 0, forcing sequential-only decoding).
 */
class BinaryArtifactReaderOffsetTest {

  private BinaryArtifactReader compileMultiColumnTable(Path dir) throws IOException {
    Path csv = dir.resolve("offsets.csv");
    try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
      // One column of each encoder family: scalar (EQ), range (BETWEEN), set (IN)
      writer.write("RULE_ID,PRIORITY,COUNTRY,AGE,REGION,DISCOUNT\n");
      writer.write("RULE_ID,PRIORITY,EQ,BETWEEN,IN,SET\n");
      writer.write("R1,30,USA,(18,65),(APAC,EMEA),0.10\n");
      writer.write("R2,20,UK,(21,70),(US),0.15\n");
      writer.write("R3,10,,,,0.05\n");
    }

    Schema schema =
        Schema.builder()
            .column("COUNTRY", ColumnType.STRING)
            .column("AGE", ColumnType.INTEGER)
            .column("REGION", ColumnType.STRING)
            .column("DISCOUNT", ColumnType.DECIMAL)
            .build();

    CompiledRuleset compiled =
        Kisoku.compiler().compile(DecisionTableSources.csv(csv), CompileOptions.production(schema));
    return BinaryArtifactReader.read(ByteBuffer.wrap(compiled.bytes()));
  }

  @Test
  void firstColumnHasZeroDataOffset(@TempDir Path tempDir) throws IOException {
    BinaryArtifactReader reader = compileMultiColumnTable(tempDir);

    // dataOffset is relative to the rule-data section base, so the first column starts at 0.
    assertEquals(0, reader.columns().get(0).dataOffset());
  }

  @Test
  void columnDataOffsetsAreStrictlyIncreasing(@TempDir Path tempDir) throws IOException {
    BinaryArtifactReader reader = compileMultiColumnTable(tempDir);

    var columns = reader.columns();
    assertTrue(columns.size() >= 2, "need multiple columns to verify offsets");

    int previous = -1;
    for (var column : columns) {
      int offset = column.dataOffset();
      assertTrue(
          offset > previous,
          "Column "
              + column.name()
              + " offset "
              + offset
              + " is not greater than previous "
              + previous);
      previous = offset;
    }
  }
}
