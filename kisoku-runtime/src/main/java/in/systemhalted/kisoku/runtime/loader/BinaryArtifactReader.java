package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.api.ArtifactKind;
import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.evaluation.RuleSelectionPolicy;
import in.systemhalted.kisoku.api.loading.LoadException;
import in.systemhalted.kisoku.runtime.csv.Operator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the binary artifact format produced by BinaryArtifactWriter.
 *
 * <p>Format layout:
 *
 * <pre>
 * Header (32 bytes)
 *   magic: 0x4B495353 ("KISS")
 *   version_major: 2 bytes
 *   version_minor: 2 bytes
 *   artifact_kind: 1 byte
 *   rule_selection: 1 byte
 *   reserved: 2 bytes
 *   column_count: 4 bytes
 *   row_count: 4 bytes
 *   dictionary_offset: 4 bytes
 *   columns_offset: 4 bytes
 *   data_offset: 4 bytes
 *
 * String Dictionary
 * Column Definitions (12 bytes each)
 * Rule Data (columnar)
 * Rule Order Index
 * </pre>
 */
final class BinaryArtifactReader {
  /** Magic bytes: "KISS" (0x4B495353) */
  static final int MAGIC = 0x4B495353;

  static final short VERSION_MAJOR = 1;

  /**
   * Highest minor version this reader understands. The reader accepts any minor version with a
   * matching major (it only rejects on major mismatch), so 1.0 and 1.1 artifacts both load.
   */
  static final short VERSION_MINOR = 1;

  static final int HEADER_SIZE = 32;
  static final int COLUMN_DEF_SIZE = 12;

  private final ByteBuffer buffer;
  private final ArtifactKind artifactKind;
  private final RuleSelectionPolicy ruleSelection;
  private final int columnCount;
  private final int rowCount;
  private final int dictionaryOffset;
  private final int columnsOffset;
  private final int dataOffset;
  private final StringDictionaryReader dictionary;
  private final List<ColumnDefinition> columns;
  private final List<ColumnDecoder> decoders;
  private final int[] ruleOrder;

  private BinaryArtifactReader(
      ByteBuffer buffer,
      ArtifactKind artifactKind,
      RuleSelectionPolicy ruleSelection,
      int columnCount,
      int rowCount,
      int dictionaryOffset,
      int columnsOffset,
      int dataOffset,
      StringDictionaryReader dictionary,
      List<ColumnDefinition> columns,
      List<ColumnDecoder> decoders,
      int[] ruleOrder) {
    this.buffer = buffer;
    this.artifactKind = artifactKind;
    this.ruleSelection = ruleSelection;
    this.columnCount = columnCount;
    this.rowCount = rowCount;
    this.dictionaryOffset = dictionaryOffset;
    this.columnsOffset = columnsOffset;
    this.dataOffset = dataOffset;
    this.dictionary = dictionary;
    this.columns = columns;
    this.decoders = decoders;
    this.ruleOrder = ruleOrder;
  }

  /**
   * Reads a binary artifact from a ByteBuffer.
   *
   * @param buffer the buffer containing the artifact (must be in BIG_ENDIAN order)
   * @return the parsed artifact reader
   */
  static BinaryArtifactReader read(ByteBuffer buffer) {
    buffer.order(ByteOrder.BIG_ENDIAN);
    buffer.position(0);

    // Read header
    int magic = buffer.getInt();
    if (magic != MAGIC) {
      throw new LoadException(
          String.format("Invalid artifact magic: expected 0x%08X, got 0x%08X", MAGIC, magic));
    }

    short versionMajor = buffer.getShort();
    short versionMinor = buffer.getShort();
    if (versionMajor != VERSION_MAJOR) {
      throw new LoadException(
          String.format(
              "Unsupported artifact version: %d.%d (expected %d.%d)",
              versionMajor, versionMinor, VERSION_MAJOR, VERSION_MINOR));
    }

    int artifactKindOrdinal = buffer.get() & 0xFF;
    int ruleSelectionOrdinal = buffer.get() & 0xFF;
    buffer.getShort(); // reserved

    int columnCount = buffer.getInt();
    int rowCount = buffer.getInt();
    int dictionaryOffset = buffer.getInt();
    int columnsOffset = buffer.getInt();
    int dataOffset = buffer.getInt();

    ArtifactKind artifactKind = artifactKindFromOrdinal(artifactKindOrdinal);
    RuleSelectionPolicy ruleSelection = ruleSelectionFromOrdinal(ruleSelectionOrdinal);

    // Read string dictionary
    StringDictionaryReader dictionary = StringDictionaryReader.read(buffer, dictionaryOffset);

    // Read column definitions
    List<ColumnDefinition> columns =
        readColumnDefinitions(buffer, columnsOffset, columnCount, dictionary);

    // Create decoders from each column's absolute base (data_offset is relative to the rule-data
    // section). Decoders read lazily through the buffer; no column data is copied onto the heap.
    List<ColumnDecoder> decoders = new ArrayList<>(columnCount);
    int dataSectionEnd = dataOffset;
    for (ColumnDefinition col : columns) {
      int base = dataOffset + col.dataOffset();
      decoders.add(createDecoder(col, buffer, base, rowCount, dictionary));
      dataSectionEnd = Math.max(dataSectionEnd, base + columnDataSize(col, buffer, base, rowCount));
    }

    // The rule order index immediately follows the rule-data section.
    int[] ruleOrder = readRuleOrder(buffer, dataSectionEnd, rowCount);

    return new BinaryArtifactReader(
        buffer,
        artifactKind,
        ruleSelection,
        columnCount,
        rowCount,
        dictionaryOffset,
        columnsOffset,
        dataOffset,
        dictionary,
        columns,
        List.copyOf(decoders),
        ruleOrder);
  }

  private static ColumnDecoder createDecoder(
      ColumnDefinition column,
      ByteBuffer buffer,
      int base,
      int rowCount,
      StringDictionaryReader dictionary) {
    return ColumnDecoder.create(column, buffer, base, rowCount, dictionary);
  }

  /**
   * Computes the encoded byte size of a column's data, used to locate the end of the rule-data
   * section (where the rule order index begins). Scalar and range sizes are formulaic; set columns
   * additionally depend on the packed all_values length, derived from the per-row offsets/lengths.
   */
  private static int columnDataSize(
      ColumnDefinition column, ByteBuffer buffer, int base, int rowCount) {
    int bitmapSize = BitMapUtils.bitmapSize(rowCount);
    return switch (column.operator()) {
      case BETWEEN_INCLUSIVE, BETWEEN_EXCLUSIVE, NOT_BETWEEN_INCLUSIVE, NOT_BETWEEN_EXCLUSIVE ->
          bitmapSize + rowCount * 4 * 2;
      case IN, NOT_IN -> {
        int offsetsBase = base + bitmapSize;
        int lengthsBase = offsetsBase + rowCount * 4;
        int totalValues = 0;
        for (int i = 0; i < rowCount; i++) {
          int end =
              buffer.getInt(offsetsBase + i * 4) + (buffer.getShort(lengthsBase + i * 2) & 0xFFFF);
          if (end > totalValues) {
            totalValues = end;
          }
        }
        yield bitmapSize + rowCount * 4 + rowCount * 2 + totalValues * 4;
      }
      default -> bitmapSize + rowCount * 4;
    };
  }

  private static List<ColumnDefinition> readColumnDefinitions(
      ByteBuffer buffer, int offset, int count, StringDictionaryReader dictionary) {
    buffer.position(offset);
    List<ColumnDefinition> columns = new ArrayList<>(count);

    for (int i = 0; i < count; i++) {
      int nameId = buffer.getInt();
      int operatorOrdinal = buffer.get() & 0xFF;
      int typeOrdinal = buffer.get() & 0xFF;
      int roleOrdinal = buffer.get() & 0xFF;
      int flags = buffer.get() & 0xFF;
      int columnDataOffset = buffer.getInt();

      String name = dictionary.get(nameId);
      Operator operator = Operator.values()[operatorOrdinal];
      ColumnType type = ColumnType.values()[typeOrdinal];
      ColumnRole role = ColumnRole.fromOrdinal(roleOrdinal);

      columns.add(
          new ColumnDefinition(nameId, name, operator, type, role, flags, columnDataOffset));
    }

    return List.copyOf(columns);
  }

  private static int[] readRuleOrder(ByteBuffer buffer, int offset, int rowCount) {
    int pos = offset;
    pos += 1; // order_type byte (unused here; evaluation order is the stored sequence)
    int[] order = new int[rowCount];
    for (int i = 0; i < rowCount; i++) {
      order[i] = buffer.getInt(pos);
      pos += 4;
    }
    return order;
  }

  private static ArtifactKind artifactKindFromOrdinal(int ordinal) {
    return switch (ordinal) {
      case 0 -> ArtifactKind.PRODUCTION;
      case 1 -> ArtifactKind.TEST_INCLUSIVE;
      default -> throw new LoadException("Unknown artifact kind ordinal: " + ordinal);
    };
  }

  private static RuleSelectionPolicy ruleSelectionFromOrdinal(int ordinal) {
    return switch (ordinal) {
      case 0 -> RuleSelectionPolicy.AUTO;
      case 1 -> RuleSelectionPolicy.PRIORITY;
      case 2 -> RuleSelectionPolicy.FIRST_MATCH;
      default -> throw new LoadException("Unknown rule selection ordinal: " + ordinal);
    };
  }

  // Accessors

  ByteBuffer buffer() {
    return buffer;
  }

  ArtifactKind artifactKind() {
    return artifactKind;
  }

  RuleSelectionPolicy ruleSelection() {
    return ruleSelection;
  }

  int columnCount() {
    return columnCount;
  }

  int rowCount() {
    return rowCount;
  }

  int dataOffset() {
    return dataOffset;
  }

  StringDictionaryReader dictionary() {
    return dictionary;
  }

  List<ColumnDefinition> columns() {
    return columns;
  }

  List<ColumnDecoder> decoders() {
    return decoders;
  }

  int[] ruleOrder() {
    return ruleOrder;
  }
}
