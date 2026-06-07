package in.systemhalted.kisoku.functional;

import static org.junit.jupiter.api.Assertions.*;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies {@link CompiledRuleset#writeTo(Path)} serializes the artifact faithfully to disk. */
class CompiledRulesetWriteToTest {

  private CompiledRuleset compileSample(Path dir) throws IOException {
    Path csv = dir.resolve("sample.csv");
    try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
      writer.write("RULE_ID,COUNTRY,DISCOUNT\n");
      writer.write("RULE_ID,EQ,SET\n");
      writer.write("R1,USA,0.10\n");
      writer.write("R2,UK,0.15\n");
    }
    Schema schema =
        Schema.builder()
            .column("COUNTRY", ColumnType.STRING)
            .column("DISCOUNT", ColumnType.DECIMAL)
            .build();
    return Kisoku.compiler()
        .compile(DecisionTableSources.csv(csv), CompileOptions.production(schema));
  }

  @Test
  void writeToProducesFileWithArtifactBytes(@TempDir Path tempDir) throws IOException {
    CompiledRuleset compiled = compileSample(tempDir);

    Path artifact = tempDir.resolve("ruleset.kss");
    compiled.writeTo(artifact);

    assertTrue(Files.exists(artifact), "artifact file should be created");
    assertArrayEquals(
        compiled.bytes(),
        Files.readAllBytes(artifact),
        "written file should byte-for-byte match the in-memory artifact");
  }
}
