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
  static final short VERSION_MINOR = 0;
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

    // Read column data and create decoders (columns are stored sequentially)
    buffer.position(dataOffset);
    List<ColumnDecoder> decoders = new ArrayList<>(columnCount);
    for (ColumnDefinition col : columns) {
      ColumnDecoder decoder = createDecoder(col, buffer, rowCount, dictionary);
      decoders.add(decoder);
    }

    // Rule order is at current position after reading all column data
    int[] ruleOrder = readRuleOrder(buffer, rowCount);

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
      ColumnDefinition column, ByteBuffer data, int rowCount, StringDictionaryReader dictionary) {
    Operator op = column.operator();
    return switch (op) {
      case RULE_ID, PRIORITY, SET, EQ, NE, GT, GTE, LT, LTE ->
          ScalarColumnDecoder.create(column, data, rowCount, dictionary);
      case BETWEEN_INCLUSIVE, BETWEEN_EXCLUSIVE, NOT_BETWEEN_INCLUSIVE, NOT_BETWEEN_EXCLUSIVE ->
          RangeColumnDecoder.create(column, data, rowCount, dictionary);
      case IN, NOT_IN -> SetMembershipColumnDecoder.create(column, data, rowCount, dictionary);
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

  private static int[] readRuleOrder(ByteBuffer buffer, int rowCount) {
    int orderType = buffer.get() & 0xFF;
    int[] order = new int[rowCount];
    for (int i = 0; i < rowCount; i++) {
      order[i] = buffer.getInt();
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
