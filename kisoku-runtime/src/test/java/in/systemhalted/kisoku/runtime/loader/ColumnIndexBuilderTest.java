package in.systemhalted.kisoku.runtime.loader;

import static org.junit.jupiter.api.Assertions.*;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.runtime.csv.Operator;
import in.systemhalted.kisoku.runtime.loader.index.CandidateBitmap;
import in.systemhalted.kisoku.runtime.loader.index.ColumnIndex;
import in.systemhalted.kisoku.runtime.loader.index.SetMembershipIndex;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link ColumnIndexBuilder} operator-to-index dispatch. */
class ColumnIndexBuilderTest {

  /**
   * Table layout (priority descending matches written order, so artifact row indices are R1=0,
   * R2=1, R3=2):
   *
   * <pre>
   * R1: AGE IN (18,25,30), REGION NOT IN (APAC)
   * R2: AGE IN (40,50),    REGION blank
   * R3: AGE blank,         REGION NOT IN (EMEA,LATAM)
   * </pre>
   */
  private BinaryArtifactReader compileSetMembershipTable(Path dir) throws IOException {
    Path csv = dir.resolve("set-membership.csv");
    try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
      writer.write("RULE_ID,PRIORITY,AGE,REGION,DISCOUNT\n");
      writer.write("RULE_ID,PRIORITY,IN,NOT IN,SET\n");
      writer.write("R1,30,(18,25,30),(APAC),0.10\n");
      writer.write("R2,20,(40,50),,0.20\n");
      writer.write("R3,10,,(EMEA,LATAM),0.05\n");
    }

    Schema schema =
        Schema.builder()
            .column("AGE", ColumnType.INTEGER)
            .column("REGION", ColumnType.STRING)
            .column("DISCOUNT", ColumnType.DECIMAL)
            .build();

    CompiledRuleset compiled =
        Kisoku.compiler().compile(DecisionTableSources.csv(csv), CompileOptions.production(schema));
    return BinaryArtifactReader.read(ByteBuffer.wrap(compiled.bytes()));
  }

  private ColumnIndex buildIndexFor(BinaryArtifactReader reader, String columnName) {
    for (int i = 0; i < reader.columns().size(); i++) {
      ColumnDefinition column = reader.columns().get(i);
      if (column.name().equals(columnName)) {
        return ColumnIndexBuilder.build(reader.decoders().get(i), column, reader.rowCount());
      }
    }
    throw new AssertionError("Column not found: " + columnName);
  }

  @Test
  void buildsSetMembershipIndexForInColumn(@TempDir Path tempDir) throws IOException {
    BinaryArtifactReader reader = compileSetMembershipTable(tempDir);

    ColumnIndex index = buildIndexFor(reader, "AGE");

    assertInstanceOf(
        SetMembershipIndex.class, index, "IN column should be indexed by SetMembershipIndex");
  }

  @Test
  void buildsSetMembershipIndexForNotInColumn(@TempDir Path tempDir) throws IOException {
    BinaryArtifactReader reader = compileSetMembershipTable(tempDir);

    ColumnIndex index = buildIndexFor(reader, "REGION");

    assertInstanceOf(
        SetMembershipIndex.class, index, "NOT_IN column should be indexed by SetMembershipIndex");
  }

  @Test
  void inIndexCandidatesIncludeMatchingSetsAndBlanks(@TempDir Path tempDir) throws IOException {
    BinaryArtifactReader reader = compileSetMembershipTable(tempDir);

    // AGE is INTEGER, so set values are stored as raw ints (no dictionary indirection)
    SetMembershipIndex index = (SetMembershipIndex) buildIndexFor(reader, "AGE");

    // 18 is in R1's set; R3 has a blank AGE cell (always matches)
    long[] candidates = index.getCandidates(18);
    assertTrue(CandidateBitmap.isSet(candidates, 0), "R1 contains 18");
    assertFalse(CandidateBitmap.isSet(candidates, 1), "R2 set is (40,50)");
    assertTrue(CandidateBitmap.isSet(candidates, 2), "R3 is blank and always matches");

    // 99 is in no set; only the blank row matches
    long[] unknown = index.getCandidates(99);
    assertEquals(1, CandidateBitmap.cardinality(unknown));
    assertTrue(CandidateBitmap.isSet(unknown, 2));
  }

  @Test
  void doesNotIndexMetadataOrOutputColumns(@TempDir Path tempDir) throws IOException {
    BinaryArtifactReader reader = compileSetMembershipTable(tempDir);

    for (int i = 0; i < reader.columns().size(); i++) {
      ColumnDefinition column = reader.columns().get(i);
      if (column.operator() == Operator.IN || column.operator() == Operator.NOT_IN) {
        continue;
      }
      if (column.operator() == Operator.RULE_ID
          || column.operator() == Operator.PRIORITY
          || column.operator() == Operator.SET) {
        assertNull(
            ColumnIndexBuilder.build(reader.decoders().get(i), column, reader.rowCount()),
            "Column " + column.name() + " should not be indexed");
      }
    }
  }
}
