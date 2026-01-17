package in.systemhalted.kisoku.testutil;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.Schema;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Generates CSV decision table fixtures for functional and scale tests. */
public final class DecisionTableFixtures {
  private DecisionTableFixtures() {}

  /** Schema for the priority table fixture. */
  public static Schema priorityTableSchema() {
    return Schema.builder()
        .column("AGE", ColumnType.INTEGER)
        .column("REGION", ColumnType.STRING)
        .column("DISCOUNT", ColumnType.DECIMAL)
        .column("TEST_EXPECTED_SEGMENT", ColumnType.STRING)
        .build();
  }

  /** Schema for the first-match table fixture. */
  public static Schema firstMatchTableSchema() {
    return Schema.builder()
        .column("AGE", ColumnType.INTEGER)
        .column("REGION", ColumnType.STRING)
        .column("DISCOUNT", ColumnType.DECIMAL)
        .build();
  }

  /** Schema for the large table fixture. */
  public static Schema largeTableSchema(int inputColumns, int outputColumns) {
    Schema.Builder builder = Schema.builder();
    for (int i = 1; i <= inputColumns; i++) {
      builder.column(String.format("FIELD_%03d", i), ColumnType.STRING);
    }
    for (int i = 1; i <= outputColumns; i++) {
      builder.column(String.format("RESULT_%03d", i), ColumnType.STRING);
    }
    return builder.build();
  }

  public static Path writePriorityTable(Path dir) throws IOException {
    List<String> header =
        List.of("RULE_ID", "PRIORITY", "AGE", "REGION", "DISCOUNT", "TEST_EXPECTED_SEGMENT");
    List<String> operators = List.of("RULE_ID", "PRIORITY", "BETWEEN", "IN", "SET", "SET");
    List<List<String>> rows =
        List.of(
            List.of("R1", "10", "(18,29)", "(APAC)", "0.05", "SEG_A"),
            List.of("R2", "20", "(18,29)", "(EMEA)", "0.10", "SEG_B"),
            List.of("R3", "5", "", "(APAC,EMEA)", "0.02", "SEG_C"));
    return writeCsv(dir, "priority.csv", header, operators, rows);
  }

  public static Path writeFirstMatchTable(Path dir) throws IOException {
    List<String> header = List.of("RULE_ID", "AGE", "REGION", "DISCOUNT");
    List<String> operators = List.of("RULE_ID", "BETWEEN", "IN", "SET");
    List<List<String>> rows =
        List.of(
            List.of("R1", "(18,29)", "(APAC,EMEA)", "0.05"),
            List.of("R2", "(18,29)", "(APAC,EMEA)", "0.10"));
    return writeCsv(dir, "first-match.csv", header, operators, rows);
  }

  public static Path writeLargeTable(Path dir, long rows, int inputColumns, int outputColumns)
      throws IOException {
    Path path = dir.resolve("large.csv");
    List<String> header = new ArrayList<>();
    header.add("RULE_ID");
    header.add("PRIORITY");
    for (int i = 1; i <= inputColumns; i++) {
      header.add(String.format("FIELD_%03d", i));
    }
    for (int i = 1; i <= outputColumns; i++) {
      header.add(String.format("RESULT_%03d", i));
    }

    List<String> operators = new ArrayList<>();
    operators.add("RULE_ID");
    operators.add("PRIORITY");
    for (int i = 1; i <= inputColumns; i++) {
      if (i == 1) {
        operators.add("IN");
      } else if (i == 2) {
        operators.add("BETWEEN");
      } else if (i == 3) {
        operators.add("NOT IN");
      } else {
        operators.add("IN");
      }
    }
    for (int i = 1; i <= outputColumns; i++) {
      operators.add("SET");
    }

    try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      writer.write(String.join(",", header));
      writer.newLine();
      writer.write(String.join(",", operators));
      writer.newLine();
      for (long row = 0; row < rows; row++) {
        StringBuilder line = new StringBuilder(512);
        line.append("R").append(row).append(',').append(row % 100);
        for (int col = 1; col <= inputColumns; col++) {
          line.append(',');
          if (col == 1) {
            line.append("(VALUE_").append(row % 100).append(')');
          } else if (col == 2) {
            line.append("(0,100)");
          } else if (col == 3) {
            line.append("(VALUE_")
                .append((row + 1) % 100)
                .append(",VALUE_")
                .append((row + 2) % 100)
                .append(')');
          }
        }
        for (int col = 1; col <= outputColumns; col++) {
          line.append(',')
              .append("RESULT_")
              .append(String.format("%03d", col))
              .append('_')
              .append(row % 10);
        }
        writer.write(line.toString());
        writer.newLine();
      }
    }
    return path;
  }

  private static Path writeCsv(
      Path dir, String name, List<String> header, List<String> operators, List<List<String>> rows)
      throws IOException {
    Path path = dir.resolve(name);
    try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      writer.write(String.join(",", header));
      writer.newLine();
      writer.write(String.join(",", operators));
      writer.newLine();
      for (List<String> row : rows) {
        writer.write(String.join(",", row));
        writer.newLine();
      }
    }
    return path;
  }
}
