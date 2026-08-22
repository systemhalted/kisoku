package in.systemhalted.kisoku.runtime.loader;

import static org.junit.jupiter.api.Assertions.*;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.runtime.codec.ValueCodec;
import in.systemhalted.kisoku.runtime.csv.Operator;
import in.systemhalted.kisoku.runtime.loader.index.ColumnIndex;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link ColumnIndexBuilder} operator dispatch over compiled column data. */
class ColumnIndexBuilderTest {

  /**
   * Table layout. Priorities ascend with written order (1 outranks 2), so the artifact keeps source
   * order and row indices are R1=0, R2=1, R3=2:
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
      writer.write("R1,10,(18,25,30),(APAC),0.10\n");
      writer.write("R2,20,(40,50),,0.20\n");
      writer.write("R3,30,,(EMEA,LATAM),0.05\n");
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
  void buildsIndexForInColumn(@TempDir Path tempDir) throws IOException {
    BinaryArtifactReader reader = compileSetMembershipTable(tempDir);

    ColumnIndex index = buildIndexFor(reader, "AGE");

    assertNotNull(index, "IN column should be indexed");
    assertTrue(index.enumerable(true), "IN candidates are enumerable");
    assertTrue(index instanceof in.systemhalted.kisoku.runtime.loader.index.PostingListIndex);
  }

  @Test
  void buildsCountOnlyIndexForNotInColumn(@TempDir Path tempDir) throws IOException {
    BinaryArtifactReader reader = compileSetMembershipTable(tempDir);

    ColumnIndex index = buildIndexFor(reader, "REGION");

    assertNotNull(index, "NOT_IN column should be indexed");
    assertFalse(index.enumerable(true), "complement match sets are not enumerable");
    assertTrue(index.enumerable(false), "absent input enumerates blanks only");
  }

  @Test
  void inIndexCandidatesIncludeMatchingSetsAndBlanks(@TempDir Path tempDir) throws IOException {
    BinaryArtifactReader reader = compileSetMembershipTable(tempDir);

    // AGE is INTEGER, so set members are stored as encoded integer codes (no dictionary lookup)
    ColumnIndex index = buildIndexFor(reader, "AGE");

    // 18 is in R1's set; R3 has a blank AGE cell (always a candidate)
    long code18 = ValueCodec.encodeInteger(18);
    assertEquals(2, index.candidateCount(code18, true), "R1 matches, R3 blank");
    assertEquals(1, index.matchEnd(code18) - index.matchStart(code18));
    assertEquals(0, index.rowAt(index.matchStart(code18)), "R1 contains 18");
    assertEquals(1, index.blankCount());
    assertEquals(2, index.blankRowAt(0), "R3 is blank");

    // 99 is in no set; only the blank row is a candidate
    long code99 = ValueCodec.encodeInteger(99);
    assertEquals(1, index.candidateCount(code99, true));
    assertEquals(index.matchStart(code99), index.matchEnd(code99), "empty match slice");
  }

  @Test
  void notInIndexCountsComplement(@TempDir Path tempDir) throws IOException {
    BinaryArtifactReader reader = compileSetMembershipTable(tempDir);
    StringDictionaryReader dictionary = reader.dictionary();

    ColumnIndex index = buildIndexFor(reader, "REGION");

    // Two condition rows (R1, R3), one blank (R2).
    // APAC is in R1's excluded set: NOT_IN candidates = R3 (condition, not containing) + R2 blank.
    long apac = dictionary.codeFor("APAC");
    assertEquals(2, index.candidateCount(apac, true));

    // A value in no excluded set: both condition rows match, plus the blank.
    long unknown = dictionary.codeFor("ZZZ_NOT_PRESENT");
    assertEquals(3, index.candidateCount(unknown, true));

    // Absent input: blanks only.
    assertEquals(1, index.candidateCount(0, false));
  }

  @Test
  void doesNotIndexMetadataOrOutputColumns(@TempDir Path tempDir) throws IOException {
    BinaryArtifactReader reader = compileSetMembershipTable(tempDir);

    for (int i = 0; i < reader.columns().size(); i++) {
      ColumnDefinition column = reader.columns().get(i);
      if (column.operator() == Operator.IN || column.operator() == Operator.NOT_IN) {
        continue;
      }
      assertNull(
          ColumnIndexBuilder.build(reader.decoders().get(i), column, reader.rowCount()),
          "column should not be indexed: " + column.name());
    }
  }
}
