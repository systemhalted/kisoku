package in.systemhalted.kisoku.runtime.compiler;

import in.systemhalted.kisoku.api.ArtifactKind;
import in.systemhalted.kisoku.api.ColumnSchema;
import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.DecisionTableSource;
import in.systemhalted.kisoku.api.RulesetMetadata;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.TableFormat;
import in.systemhalted.kisoku.api.compilation.CompilationException;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.compilation.RulesetCompiler;
import in.systemhalted.kisoku.api.evaluation.RuleSelectionPolicy;
import in.systemhalted.kisoku.runtime.csv.Operator;
import in.systemhalted.kisoku.runtime.csv.StreamingCsvRowReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Compiles CSV decision tables into binary artifacts. */
public final class CsvRulesetCompiler implements RulesetCompiler {

  @Override
  public CompiledRuleset compile(DecisionTableSource source, CompileOptions options) {
    if (source.format() != TableFormat.CSV) {
      throw new CompilationException("Only CSV sources are supported: " + source.format());
    }

    try {
      return doCompile(source, options);
    } catch (IOException e) {
      throw new CompilationException("Failed to read CSV source: " + e.getMessage(), e);
    }
  }

  private CompiledRuleset doCompile(DecisionTableSource source, CompileOptions options)
      throws IOException {
    Schema schema = options.schema();
    ArtifactKind artifactKind = options.artifactKind();
    RuleSelectionPolicy ruleSelection = options.ruleSelectionPolicy();
    String priorityColumnName = options.priorityColumn();

    // Parse CSV
    List<String[]> allRows = new ArrayList<>();
    String[] headerRow;
    String[] operatorRow;

    try (StreamingCsvRowReader reader = new StreamingCsvRowReader(source.openStream())) {
      headerRow = reader.readNext();
      if (headerRow == null) {
        throw new CompilationException("Missing header row");
      }

      operatorRow = reader.readNext();
      if (operatorRow == null) {
        throw new CompilationException("Missing operator row");
      }

      String[] dataRow;
      while ((dataRow = reader.readNext()) != null) {
        allRows.add(dataRow);
      }
    }

    if (allRows.isEmpty()) {
      throw new CompilationException("No data rows found");
    }

    // Parse operators
    Operator[] operators = new Operator[operatorRow.length];
    for (int i = 0; i < operatorRow.length; i++) {
      operators[i] = Operator.fromToken(operatorRow[i]);
    }

    // Build column info and filter TEST_ columns for production
    List<ColumnInfo> columns = buildColumnInfo(headerRow, operators, schema, artifactKind);

    // Find priority column index if present
    int priorityIndex = findColumnIndex(headerRow, priorityColumnName);
    boolean hasPriority = priorityIndex >= 0 && operators[priorityIndex] == Operator.PRIORITY;

    // Build string dictionary (first pass)
    StringDictionary dictionary = buildDictionary(headerRow, columns, allRows);

    // Sort rows if using priority
    List<Integer> ruleOrder = buildRuleOrder(allRows, priorityIndex, hasPriority, ruleSelection);

    // Reorder rows according to rule order
    List<String[]> orderedRows = new ArrayList<>();
    for (int idx : ruleOrder) {
      orderedRows.add(allRows.get(idx));
    }

    // Encode columns
    byte[] columnDefinitionsBytes = encodeColumnDefinitions(columns, dictionary);
    byte[] ruleDataBytes = encodeRuleData(columns, orderedRows, dictionary);
    byte[] ruleOrderBytes = encodeRuleOrder(ruleOrder, hasPriority, ruleSelection);
    byte[] dictionaryBytes = dictionary.serialize();

    // Build artifact
    BinaryArtifactWriter writer = new BinaryArtifactWriter();
    byte[] artifactBytes =
        writer.write(
            artifactKind,
            ruleSelection,
            columns.size(),
            orderedRows.size(),
            dictionaryBytes,
            columnDefinitionsBytes,
            ruleDataBytes,
            ruleOrderBytes);

    // Build metadata
    List<String> inputColumns = new ArrayList<>();
    List<String> outputColumns = new ArrayList<>();
    for (ColumnInfo col : columns) {
      if (col.operator.isOutput()) {
        outputColumns.add(col.name);
      } else if (col.operator != Operator.RULE_ID && col.operator != Operator.PRIORITY) {
        inputColumns.add(col.name);
      }
    }

    String effectivePriorityColumn = hasPriority ? priorityColumnName : null;
    RulesetMetadata metadata =
        new RulesetMetadata(
            orderedRows.size(), inputColumns, outputColumns, effectivePriorityColumn, artifactKind);

    return new CompiledRulesetImpl(artifactKind, metadata, artifactBytes);
  }

  private List<ColumnInfo> buildColumnInfo(
      String[] headerRow, Operator[] operators, Schema schema, ArtifactKind artifactKind) {
    List<ColumnInfo> columns = new ArrayList<>();

    for (int i = 0; i < headerRow.length; i++) {
      String name = headerRow[i];
      Operator operator = operators[i];

      // Mark TEST_ columns but include in all artifacts (flagged for evaluation-time control)
      boolean isTestColumn = name.startsWith("TEST_");

      ColumnType type = resolveColumnType(name, operator, schema);
      int role = resolveColumnRole(operator);

      columns.add(new ColumnInfo(i, name, operator, type, role, isTestColumn));
    }

    return columns;
  }

  private ColumnType resolveColumnType(String name, Operator operator, Schema schema) {
    // Reserved columns have implicit types
    if (operator == Operator.RULE_ID) {
      return ColumnType.STRING;
    }
    if (operator == Operator.PRIORITY) {
      return ColumnType.INTEGER;
    }

    // Look up in schema
    return schema
        .column(name)
        .map(ColumnSchema::type)
        .orElseThrow(() -> new CompilationException("Column '" + name + "' not found in schema"));
  }

  private int resolveColumnRole(Operator operator) {
    if (operator.isOutput()) {
      return 1; // OUTPUT
    }
    if (operator == Operator.RULE_ID || operator == Operator.PRIORITY) {
      return 2; // METADATA
    }
    return 0; // INPUT
  }

  private int findColumnIndex(String[] headerRow, String columnName) {
    for (int i = 0; i < headerRow.length; i++) {
      if (columnName.equals(headerRow[i])) {
        return i;
      }
    }
    return -1;
  }

  private StringDictionary buildDictionary(
      String[] headerRow, List<ColumnInfo> columns, List<String[]> rows) {
    StringDictionary dictionary = new StringDictionary();

    // Add column names
    for (ColumnInfo col : columns) {
      dictionary.add(col.name);
    }

    // Add all string values from data rows
    for (String[] row : rows) {
      for (ColumnInfo col : columns) {
        if (col.originalIndex >= row.length) {
          continue;
        }
        String value = row[col.originalIndex];
        if (value == null || value.isEmpty()) {
          continue;
        }

        // For set operators, parse and add individual values
        if (col.operator == Operator.IN || col.operator == Operator.NOT_IN) {
          addSetValuesToDictionary(value, dictionary);
        }
        // For range operators, parse and add min/max
        else if (isRangeOperator(col.operator)) {
          addRangeValuesToDictionary(value, col.type, dictionary);
        }
        // For scalar string/decimal types, add directly
        else if (col.type == ColumnType.STRING || col.type == ColumnType.DECIMAL) {
          dictionary.add(value);
        }
      }
    }

    return dictionary;
  }

  private void addSetValuesToDictionary(String value, StringDictionary dictionary) {
    if (!value.startsWith("(") || !value.endsWith(")")) {
      return;
    }
    String inner = value.substring(1, value.length() - 1);
    if (inner.isEmpty()) {
      return;
    }
    for (String part : inner.split(",")) {
      dictionary.add(part.trim());
    }
  }

  private void addRangeValuesToDictionary(
      String value, ColumnType type, StringDictionary dictionary) {
    if (type != ColumnType.STRING && type != ColumnType.DECIMAL) {
      return;
    }
    if (!value.startsWith("(") || !value.endsWith(")")) {
      return;
    }
    String inner = value.substring(1, value.length() - 1);
    String[] parts = inner.split(",", 2);
    if (parts.length == 2) {
      dictionary.add(parts[0].trim());
      dictionary.add(parts[1].trim());
    }
  }

  private boolean isRangeOperator(Operator op) {
    return op == Operator.BETWEEN_INCLUSIVE
        || op == Operator.BETWEEN_EXCLUSIVE
        || op == Operator.NOT_BETWEEN_INCLUSIVE
        || op == Operator.NOT_BETWEEN_EXCLUSIVE;
  }

  private List<Integer> buildRuleOrder(
      List<String[]> rows, int priorityIndex, boolean hasPriority, RuleSelectionPolicy policy) {
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      order.add(i);
    }

    // Sort by priority if applicable
    boolean usePriority =
        hasPriority
            && (policy == RuleSelectionPolicy.PRIORITY || policy == RuleSelectionPolicy.AUTO);

    if (usePriority) {
      final int pIdx = priorityIndex;
      order.sort(
          Comparator.comparingInt(
                  (Integer i) -> {
                    String[] row = rows.get(i);
                    if (pIdx >= row.length) return 0;
                    String val = row[pIdx];
                    if (val == null || val.isEmpty()) return 0;
                    return Integer.parseInt(val.trim());
                  })
              .reversed()); // Descending order (higher priority first)
    }

    return order;
  }

  private byte[] encodeColumnDefinitions(List<ColumnInfo> columns, StringDictionary dictionary) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    int dataOffset = 0;

    for (ColumnInfo col : columns) {
      int nameId = dictionary.getId(col.name);
      int operatorOrdinal = col.operator.ordinal();
      int typeOrdinal = col.type.ordinal();
      int flags = col.isTestColumn ? 0x02 : 0x00;

      byte[] colDef =
          BinaryArtifactWriter.writeColumnDefinition(
              nameId, operatorOrdinal, typeOrdinal, col.role, flags, dataOffset);
      try {
        baos.write(colDef);
      } catch (IOException e) {
        throw new CompilationException("Failed to write column definition", e);
      }

      // Update data offset (this is simplified - actual size depends on encoder)
      // For now we'll compute actual offsets during data encoding
    }

    return baos.toByteArray();
  }

  private byte[] encodeRuleData(
      List<ColumnInfo> columns, List<String[]> rows, StringDictionary dictionary) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    // Project rows to only include columns we're encoding
    List<String[]> projectedRows = new ArrayList<>();
    for (String[] row : rows) {
      String[] projected = new String[columns.size()];
      for (int c = 0; c < columns.size(); c++) {
        ColumnInfo col = columns.get(c);
        projected[c] = col.originalIndex < row.length ? row[col.originalIndex] : "";
      }
      projectedRows.add(projected);
    }

    for (int c = 0; c < columns.size(); c++) {
      ColumnInfo col = columns.get(c);
      ColumnEncoder encoder = createEncoder(col.operator, dictionary, col.type);
      byte[] encoded = encoder.encode(projectedRows, c);
      try {
        baos.write(encoded);
      } catch (IOException e) {
        throw new CompilationException("Failed to encode column: " + col.name, e);
      }
    }

    return baos.toByteArray();
  }

  private ColumnEncoder createEncoder(
      Operator operator, StringDictionary dictionary, ColumnType type) {
    return switch (operator) {
      case IN, NOT_IN -> new SetColumnEncoder(dictionary, type);
      case BETWEEN_INCLUSIVE, BETWEEN_EXCLUSIVE, NOT_BETWEEN_INCLUSIVE, NOT_BETWEEN_EXCLUSIVE ->
          new RangeColumnEncoder(dictionary, type);
      default -> new ScalarColumnEncoder(dictionary, type);
    };
  }

  private byte[] encodeRuleOrder(
      List<Integer> ruleOrder, boolean hasPriority, RuleSelectionPolicy policy) {
    int orderType =
        (hasPriority
                && (policy == RuleSelectionPolicy.PRIORITY || policy == RuleSelectionPolicy.AUTO))
            ? 1
            : 0;
    return BinaryArtifactWriter.writeRuleOrderIndex(orderType, ruleOrder);
  }

  /** Internal column metadata during compilation. */
  private record ColumnInfo(
      int originalIndex,
      String name,
      Operator operator,
      ColumnType type,
      int role,
      boolean isTestColumn) {}
}
