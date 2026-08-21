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
import in.systemhalted.kisoku.runtime.codec.ValueCodec;
import in.systemhalted.kisoku.runtime.csv.Operator;
import in.systemhalted.kisoku.runtime.csv.StreamingCsvRowReader;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles CSV decision tables into binary artifacts, streaming end to end.
 *
 * <p>The source is read twice and never held in memory:
 *
 * <ol>
 *   <li><b>Pass 1</b> streams the CSV to collect what encoding needs up front: the string
 *       dictionary (bounded by distinct values, not rows), per-column decimal scales, the priority
 *       of every row (one int each), and the row count.
 *   <li><b>Pass 2</b> streams the CSV again, encoding each cell to its order-preserving code as it
 *       is read and appending it to a per-column temporary file (in source row order).
 *   <li><b>Stitch</b> writes the artifact file sequentially: header, dictionary, column
 *       definitions, then each column's data - loading one column's temporary file at a time,
 *       permuting its rows into evaluation order in memory, and emitting the column's subsections.
 * </ol>
 *
 * <p>Peak heap is therefore the dictionary, one int per row for priorities, and the largest single
 * column's encoded data - independent of the table's total size. The artifact is written through a
 * stream with 64-bit offsets (format 4.0), so it may exceed 2 GB; each individual column's data
 * must stay under 2 GB, which the per-column loader mapping also requires.
 */
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

    // ---- Pass 1: headers, dictionary, scales, priorities, row count
    FirstPass first = firstPass(source, schema, priorityColumnName);
    List<ColumnInfo> columns = first.columns;
    int rowCount = first.rowCount;

    if (rowCount == 0) {
      throw new CompilationException("No data rows found");
    }

    boolean usePriority =
        first.hasPriority
            && (ruleSelection == RuleSelectionPolicy.PRIORITY
                || ruleSelection == RuleSelectionPolicy.AUTO);
    // evalToSource[d] = source row index of the d-th rule in evaluation order; null = identity.
    int[] evalToSource = usePriority ? stableArgsortAscending(first.priorities, rowCount) : null;

    // ---- Pass 2: encode cells to per-column temporary files in source order
    Path tempDir = Files.createTempDirectory("kisoku-compile");
    Path artifactFile;
    try {
      ColumnTotals totals = secondPass(source, columns, first.scales, first.dictionary, tempDir);

      // ---- Stitch: layout, then sequential write
      artifactFile = Files.createTempFile("kisoku-artifact", ".kbin");
      artifactFile.toFile().deleteOnExit();
      stitch(
          artifactFile,
          artifactKind,
          ruleSelection,
          columns,
          rowCount,
          first.dictionary,
          first.scales,
          totals,
          evalToSource,
          tempDir);
    } finally {
      deleteRecursively(tempDir);
    }

    // ---- Metadata
    List<String> inputColumns = new ArrayList<>();
    List<String> outputColumns = new ArrayList<>();
    for (ColumnInfo col : columns) {
      if (col.operator.isOutput()) {
        outputColumns.add(col.name);
      } else if (col.operator != Operator.RULE_ID && col.operator != Operator.PRIORITY) {
        inputColumns.add(col.name);
      }
    }
    String effectivePriorityColumn = first.hasPriority ? priorityColumnName : null;
    RulesetMetadata metadata =
        new RulesetMetadata(
            rowCount, inputColumns, outputColumns, effectivePriorityColumn, artifactKind);

    return new CompiledRulesetImpl(artifactKind, metadata, artifactFile);
  }

  // ------------------------------------------------------------------ pass 1

  /** Everything pass 1 learns about the table. */
  private static final class FirstPass {
    List<ColumnInfo> columns;
    int rowCount;
    int[] priorities; // per source row; only meaningful when hasPriority
    boolean hasPriority;
    int[] scales; // per column
    StringDictionary dictionary;
  }

  private FirstPass firstPass(DecisionTableSource source, Schema schema, String priorityColumnName)
      throws IOException {
    FirstPass result = new FirstPass();
    result.dictionary = new StringDictionary();

    try (StreamingCsvRowReader reader = new StreamingCsvRowReader(source.openStream())) {
      String[] headerRow = reader.readNext();
      if (headerRow == null) {
        throw new CompilationException("Missing header row");
      }
      String[] operatorRow = reader.readNext();
      if (operatorRow == null) {
        throw new CompilationException("Missing operator row");
      }

      Operator[] operators = new Operator[operatorRow.length];
      for (int i = 0; i < operatorRow.length; i++) {
        operators[i] = Operator.fromToken(operatorRow[i]);
      }
      result.columns = buildColumnInfo(headerRow, operators, schema);

      // Column names live in the dictionary; definitions reference them by ID.
      for (ColumnInfo col : result.columns) {
        result.dictionary.add(col.name);
      }

      int priorityIndex = findColumnIndex(headerRow, priorityColumnName);
      result.hasPriority = priorityIndex >= 0 && operators[priorityIndex] == Operator.PRIORITY;

      result.scales = new int[result.columns.size()];
      int[] priorities = new int[1024];
      int rows = 0;

      String[] row;
      while ((row = reader.readNext()) != null) {
        if (result.hasPriority) {
          if (rows == priorities.length) {
            int[] grown = new int[priorities.length * 2];
            System.arraycopy(priorities, 0, grown, 0, rows);
            priorities = grown;
          }
          priorities[rows] = priorityOf(row, priorityIndex);
        }
        collectDictionaryAndScales(row, result.columns, result.scales, result.dictionary);
        rows++;
      }

      result.rowCount = rows;
      result.priorities = priorities;
    }
    return result;
  }

  /** Feeds one row's cells into the dictionary and decimal-scale maxima. */
  private void collectDictionaryAndScales(
      String[] row, List<ColumnInfo> columns, int[] scales, StringDictionary dictionary) {
    for (int c = 0; c < columns.size(); c++) {
      ColumnInfo col = columns.get(c);
      String value = cellAt(row, col.originalIndex);
      if (value.isEmpty()) {
        continue;
      }

      if (col.type == ColumnType.DECIMAL) {
        for (String operand : splitOperands(value)) {
          try {
            scales[c] = Math.max(scales[c], ValueCodec.scaleOf(operand));
          } catch (NumberFormatException e) {
            throw new CompilationException(
                "Column '" + col.name + "' is DECIMAL but holds '" + operand + "'", e);
          }
        }
        continue;
      }

      // Only STRING values are dictionary-encoded; RULE_ID is stored as inline UTF-8, and
      // DECIMAL/TIMESTAMP as order-preserving numbers.
      if (col.type != ColumnType.STRING || col.operator == Operator.RULE_ID) {
        continue;
      }
      if (col.operator == Operator.IN || col.operator == Operator.NOT_IN) {
        for (String operand : splitOperands(value)) {
          dictionary.add(operand);
        }
      } else if (isRangeOperator(col.operator)) {
        for (String operand : splitOperands(value)) {
          dictionary.add(operand);
        }
      } else {
        dictionary.add(value.trim());
      }
    }
  }

  // ------------------------------------------------------------------ pass 2

  /** Per-column aggregates pass 2 tracks for layout computation. */
  private static final class ColumnTotals {
    long[] setMembers; // total set members per column
    long[] inlineBytes; // total inline UTF-8 bytes per column
  }

  /**
   * Streams the CSV a second time, appending each cell's encoded record to its column's temporary
   * file in source row order. Record formats (per row, per column):
   *
   * <pre>
   * scalar:  present:1 [code:8]
   * range:   present:1 [min:8 max:8]
   * set:     present:1 [count:2 codes:8*count]
   * ruleid:  present:1 [len:2 utf8-bytes]
   * </pre>
   */
  private ColumnTotals secondPass(
      DecisionTableSource source,
      List<ColumnInfo> columns,
      int[] scales,
      StringDictionary dictionary,
      Path tempDir)
      throws IOException {
    ColumnTotals totals = new ColumnTotals();
    totals.setMembers = new long[columns.size()];
    totals.inlineBytes = new long[columns.size()];

    DataOutputStream[] streams = new DataOutputStream[columns.size()];
    try {
      for (int c = 0; c < columns.size(); c++) {
        streams[c] =
            new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(columnTemp(tempDir, c)), 1 << 15));
      }

      try (StreamingCsvRowReader reader = new StreamingCsvRowReader(source.openStream())) {
        reader.readNext(); // header row (validated in pass 1)
        reader.readNext(); // operator row
        String[] row;
        long rowNumber = 2;
        while ((row = reader.readNext()) != null) {
          rowNumber++;
          try {
            encodeRow(row, columns, scales, dictionary, streams, totals);
          } catch (CompilationException e) {
            throw new CompilationException("Row " + rowNumber + ": " + e.getMessage(), e);
          }
        }
      }
    } finally {
      for (DataOutputStream stream : streams) {
        if (stream != null) {
          stream.close();
        }
      }
    }
    return totals;
  }

  private void encodeRow(
      String[] row,
      List<ColumnInfo> columns,
      int[] scales,
      StringDictionary dictionary,
      DataOutputStream[] streams,
      ColumnTotals totals)
      throws IOException {
    for (int c = 0; c < columns.size(); c++) {
      ColumnInfo col = columns.get(c);
      DataOutputStream out = streams[c];
      String value = cellAt(row, col.originalIndex);

      if (value.isEmpty()) {
        out.writeByte(0);
        continue;
      }
      out.writeByte(1);

      if (col.operator == Operator.RULE_ID) {
        byte[] bytes = value.trim().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xFFFF) {
          throw new CompilationException("RULE_ID exceeds 65535 UTF-8 bytes");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
        totals.inlineBytes[c] += bytes.length;
      } else if (col.operator == Operator.IN || col.operator == Operator.NOT_IN) {
        long[] codes = CellEncoder.setCodes(value, col.type, scales[c], dictionary);
        out.writeShort(codes.length);
        for (long code : codes) {
          out.writeLong(code);
        }
        totals.setMembers[c] += codes.length;
      } else if (isRangeOperator(col.operator)) {
        long[] range = CellEncoder.rangeCodes(value, col.type, scales[c], dictionary);
        out.writeLong(range[0]);
        out.writeLong(range[1]);
      } else {
        out.writeLong(CellEncoder.scalarCode(value.trim(), col.type, scales[c], dictionary));
      }
    }
  }

  // ------------------------------------------------------------------ stitch

  private void stitch(
      Path artifactFile,
      ArtifactKind artifactKind,
      RuleSelectionPolicy ruleSelection,
      List<ColumnInfo> columns,
      int rowCount,
      StringDictionary dictionary,
      int[] scales,
      ColumnTotals totals,
      int[] evalToSource,
      Path tempDir)
      throws IOException {
    byte[] dictionaryBytes = dictionary.serialize();

    // Layout: every section size is known before anything is written.
    int bitmapSize = (rowCount + 7) / 8;
    long[] columnOffsets = new long[columns.size()]; // relative to the data section
    long dataSize = 0;
    for (int c = 0; c < columns.size(); c++) {
      columnOffsets[c] = dataSize;
      long size = columnSize(columns.get(c), rowCount, bitmapSize, totals, c);
      if (size > Integer.MAX_VALUE) {
        throw new CompilationException(
            "Column '"
                + columns.get(c).name
                + "' data is "
                + size
                + " bytes; the per-column"
                + " limit is 2GB");
      }
      dataSize += size;
    }

    long dictionaryOffset = BinaryArtifactWriter.HEADER_SIZE;
    long columnsOffset = dictionaryOffset + dictionaryBytes.length;
    long dataOffset =
        columnsOffset + (long) columns.size() * BinaryArtifactWriter.COLUMN_DEFINITION_SIZE;
    long ruleOrderOffset = dataOffset + dataSize;

    try (DataOutputStream dos =
        new DataOutputStream(
            new BufferedOutputStream(Files.newOutputStream(artifactFile), 1 << 16))) {
      BinaryArtifactWriter.writeHeader(
          dos,
          artifactKind,
          ruleSelection,
          columns.size(),
          rowCount,
          dictionaryOffset,
          columnsOffset,
          dataOffset,
          ruleOrderOffset);
      dos.write(dictionaryBytes);

      for (int c = 0; c < columns.size(); c++) {
        ColumnInfo col = columns.get(c);
        BinaryArtifactWriter.writeColumnDefinition(
            dos,
            dictionary.getId(col.name),
            col.operator.ordinal(),
            col.type.ordinal(),
            resolveColumnRole(col.operator),
            col.isTestColumn ? 0x02 : 0x00,
            columnOffsets[c],
            scales[c]);
      }

      for (int c = 0; c < columns.size(); c++) {
        writeColumn(dos, columns.get(c), c, rowCount, bitmapSize, evalToSource, tempDir);
        Files.delete(columnTemp(tempDir, c)); // free disk as we go
      }

      // Rows are physically stored in evaluation order, so the order is the identity.
      dos.writeByte(BinaryArtifactWriter.RULE_ORDER_IDENTITY);
    }
  }

  /** Artifact byte size of one column's data section. */
  private long columnSize(
      ColumnInfo col, int rowCount, int bitmapSize, ColumnTotals totals, int c) {
    if (col.operator == Operator.RULE_ID) {
      return bitmapSize + 4L * (rowCount + 1) + totals.inlineBytes[c];
    }
    if (col.operator == Operator.IN || col.operator == Operator.NOT_IN) {
      return bitmapSize + 4L * rowCount + 2L * rowCount + 8L * totals.setMembers[c];
    }
    if (isRangeOperator(col.operator)) {
      return bitmapSize + 16L * rowCount;
    }
    return bitmapSize + 8L * rowCount;
  }

  /**
   * Emits one column's artifact data: loads the column's temporary file, indexes its per-row
   * records, and writes each subsection by iterating rows in evaluation order.
   */
  private void writeColumn(
      DataOutputStream dos,
      ColumnInfo col,
      int c,
      int rowCount,
      int bitmapSize,
      int[] evalToSource,
      Path tempDir)
      throws IOException {
    byte[] temp = Files.readAllBytes(columnTemp(tempDir, c));
    ByteBuffer buf = ByteBuffer.wrap(temp);

    // Index record starts (records are self-describing).
    int[] recStart = new int[rowCount];
    int pos = 0;
    for (int r = 0; r < rowCount; r++) {
      recStart[r] = pos;
      boolean present = temp[pos] == 1;
      pos += 1;
      if (!present) {
        continue;
      }
      if (col.operator == Operator.RULE_ID) {
        pos += 2 + (buf.getShort(pos) & 0xFFFF);
      } else if (col.operator == Operator.IN || col.operator == Operator.NOT_IN) {
        pos += 2 + 8 * (buf.getShort(pos) & 0xFFFF);
      } else if (isRangeOperator(col.operator)) {
        pos += 16;
      } else {
        pos += 8;
      }
    }

    // Presence bitmap (MSB-first), in evaluation order.
    byte[] bitmap = new byte[bitmapSize];
    for (int d = 0; d < rowCount; d++) {
      int src = evalToSource != null ? evalToSource[d] : d;
      if (temp[recStart[src]] == 1) {
        bitmap[d / 8] |= (byte) (1 << (7 - (d % 8)));
      }
    }
    dos.write(bitmap);

    if (col.operator == Operator.RULE_ID) {
      long running = 0;
      for (int d = 0; d < rowCount; d++) {
        int src = evalToSource != null ? evalToSource[d] : d;
        dos.writeInt((int) running);
        if (temp[recStart[src]] == 1) {
          running += buf.getShort(recStart[src] + 1) & 0xFFFF;
        }
      }
      dos.writeInt((int) running); // blob size
      for (int d = 0; d < rowCount; d++) {
        int src = evalToSource != null ? evalToSource[d] : d;
        if (temp[recStart[src]] == 1) {
          int len = buf.getShort(recStart[src] + 1) & 0xFFFF;
          dos.write(temp, recStart[src] + 3, len);
        }
      }
    } else if (col.operator == Operator.IN || col.operator == Operator.NOT_IN) {
      int running = 0;
      for (int d = 0; d < rowCount; d++) { // list_offsets
        int src = evalToSource != null ? evalToSource[d] : d;
        dos.writeInt(running);
        if (temp[recStart[src]] == 1) {
          running += buf.getShort(recStart[src] + 1) & 0xFFFF;
        }
      }
      for (int d = 0; d < rowCount; d++) { // list_lengths
        int src = evalToSource != null ? evalToSource[d] : d;
        dos.writeShort(temp[recStart[src]] == 1 ? buf.getShort(recStart[src] + 1) : 0);
      }
      for (int d = 0; d < rowCount; d++) { // all_values
        int src = evalToSource != null ? evalToSource[d] : d;
        if (temp[recStart[src]] == 1) {
          int count = buf.getShort(recStart[src] + 1) & 0xFFFF;
          for (int i = 0; i < count; i++) {
            dos.writeLong(buf.getLong(recStart[src] + 3 + i * 8));
          }
        }
      }
    } else if (isRangeOperator(col.operator)) {
      for (int d = 0; d < rowCount; d++) { // min_values
        int src = evalToSource != null ? evalToSource[d] : d;
        dos.writeLong(temp[recStart[src]] == 1 ? buf.getLong(recStart[src] + 1) : 0L);
      }
      for (int d = 0; d < rowCount; d++) { // max_values
        int src = evalToSource != null ? evalToSource[d] : d;
        dos.writeLong(temp[recStart[src]] == 1 ? buf.getLong(recStart[src] + 9) : 0L);
      }
    } else {
      for (int d = 0; d < rowCount; d++) { // values
        int src = evalToSource != null ? evalToSource[d] : d;
        dos.writeLong(temp[recStart[src]] == 1 ? buf.getLong(recStart[src] + 1) : 0L);
      }
    }
  }

  // ----------------------------------------------------------------- shared

  private List<ColumnInfo> buildColumnInfo(
      String[] headerRow, Operator[] operators, Schema schema) {
    List<ColumnInfo> columns = new ArrayList<>();
    for (int i = 0; i < headerRow.length; i++) {
      String name = headerRow[i];
      Operator operator = operators[i];
      boolean isTestColumn = name.startsWith("TEST_");
      ColumnType type = resolveColumnType(name, operator, schema);
      columns.add(new ColumnInfo(i, name, operator, type, isTestColumn));
    }
    return columns;
  }

  private ColumnType resolveColumnType(String name, Operator operator, Schema schema) {
    if (operator == Operator.RULE_ID) {
      return ColumnType.STRING;
    }
    if (operator == Operator.PRIORITY) {
      return ColumnType.INTEGER;
    }
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

  /** Reads a row's priority; an absent or blank value ranks last. */
  private int priorityOf(String[] row, int priorityIndex) {
    if (priorityIndex >= row.length) {
      return Integer.MAX_VALUE;
    }
    String value = row[priorityIndex];
    if (value == null || value.isBlank()) {
      return Integer.MAX_VALUE;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new CompilationException("PRIORITY value '" + value + "' is not an integer", e);
    }
  }

  /**
   * Stable ascending argsort of the first {@code count} priorities: a lower value means a higher
   * priority, and ties keep source order.
   *
   * @return evalToSource: entry d is the source row of the d-th rule in evaluation order
   */
  private static int[] stableArgsortAscending(int[] keys, int count) {
    int[] order = new int[count];
    for (int i = 0; i < count; i++) {
      order[i] = i;
    }
    int[] tmp = new int[count];
    for (int width = 1; width < count; width *= 2) {
      for (int lo = 0; lo < count - width; lo += 2 * width) {
        int mid = lo + width;
        int hi = Math.min(lo + 2 * width, count);
        if (keys[order[mid - 1]] <= keys[order[mid]]) {
          continue;
        }
        int i = lo;
        int j = mid;
        int k = lo;
        while (i < mid && j < hi) {
          tmp[k++] = keys[order[i]] <= keys[order[j]] ? order[i++] : order[j++];
        }
        while (i < mid) {
          tmp[k++] = order[i++];
        }
        while (j < hi) {
          tmp[k++] = order[j++];
        }
        System.arraycopy(tmp, lo, order, lo, hi - lo);
      }
    }
    return order;
  }

  /** Splits a cell into its operands: a bare scalar, or the members of a {@code (a,b,c)} cell. */
  private List<String> splitOperands(String value) {
    String trimmed = value.trim();
    if (!trimmed.startsWith("(") || !trimmed.endsWith(")")) {
      return List.of(trimmed);
    }
    String inner = trimmed.substring(1, trimmed.length() - 1);
    if (inner.isBlank()) {
      return List.of();
    }
    List<String> operands = new ArrayList<>();
    for (String part : inner.split(",")) {
      if (!part.isBlank()) {
        operands.add(part.trim());
      }
    }
    return operands;
  }

  private boolean isRangeOperator(Operator op) {
    return op == Operator.BETWEEN_INCLUSIVE
        || op == Operator.BETWEEN_EXCLUSIVE
        || op == Operator.NOT_BETWEEN_INCLUSIVE
        || op == Operator.NOT_BETWEEN_EXCLUSIVE;
  }

  private static String cellAt(String[] row, int index) {
    if (index >= row.length || row[index] == null) {
      return "";
    }
    return row[index];
  }

  private static Path columnTemp(Path tempDir, int column) {
    return tempDir.resolve("col-" + column + ".tmp");
  }

  private static void deleteRecursively(Path dir) {
    try (var paths = Files.walk(dir)) {
      paths
          .sorted((a, b) -> b.getNameCount() - a.getNameCount())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException e) {
                  // Best-effort temp cleanup.
                }
              });
    } catch (IOException e) {
      // Best-effort temp cleanup.
    }
  }

  /** Internal column metadata during compilation. */
  private record ColumnInfo(
      int originalIndex, String name, Operator operator, ColumnType type, boolean isTestColumn) {}
}
