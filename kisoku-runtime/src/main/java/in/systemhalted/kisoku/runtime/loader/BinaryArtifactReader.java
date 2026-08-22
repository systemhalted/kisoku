package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.api.ArtifactKind;
import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.evaluation.RuleSelectionPolicy;
import in.systemhalted.kisoku.api.loading.LoadException;
import in.systemhalted.kisoku.runtime.csv.Operator;
import in.systemhalted.kisoku.runtime.loader.index.ColumnIndex;
import in.systemhalted.kisoku.runtime.loader.index.PostingListIndex;
import in.systemhalted.kisoku.runtime.loader.index.RangeIntervalIndex;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the binary artifact format produced by BinaryArtifactWriter.
 *
 * <p>Format layout (all offsets 64-bit; the artifact may exceed 2 GB):
 *
 * <pre>
 * Header (64 bytes)
 *   magic: 0x4B495353 ("KISS")
 *   version_major: 2 bytes
 *   version_minor: 2 bytes
 *   artifact_kind: 1 byte
 *   rule_selection: 1 byte
 *   reserved: 2 bytes
 *   column_count: 4 bytes
 *   row_count: 4 bytes
 *   dictionary_offset: 8 bytes
 *   columns_offset: 8 bytes
 *   data_offset: 8 bytes
 *   rule_order_offset: 8 bytes
 *   reserved: 12 bytes
 *
 * String Dictionary
 * Column Definitions (24 bytes each)
 * Rule Data (columnar; each column's data under 2 GB)
 * Rule Order (order_type byte; 0 = identity, 1 = explicit int[row_count])
 * </pre>
 *
 * <p>Two read paths:
 *
 * <ul>
 *   <li>{@link #read(ByteBuffer)} - the whole artifact in one buffer. Only possible for artifacts
 *       that fit a single buffer (≤ 2 GB), which is guaranteed for anything that arrived as a
 *       {@code byte[]}.
 *   <li>{@link #readMapped(FileChannel)} - maps the metadata region once and each column's data
 *       slice as its own buffer, so no single mapping needs to span 2 GB and the artifact size is
 *       unbounded.
 * </ul>
 */
final class BinaryArtifactReader {
  /** Magic bytes: "KISS" (0x4B495353) */
  static final int MAGIC = 0x4B495353;

  static final short VERSION_MAJOR = 4;

  /**
   * Highest minor version this reader understands. The reader accepts any minor version with a
   * matching major (it only rejects on major mismatch); a 4.0 artifact simply has no persisted
   * index section (index_offset reads as zero).
   */
  static final short VERSION_MINOR = 1;

  static final int HEADER_SIZE = 64;
  static final int COLUMN_DEF_SIZE = 24;

  /** Rule-order type: rows are stored in evaluation order; no explicit array follows. */
  static final int RULE_ORDER_IDENTITY = 0;

  /** Rule-order type: an explicit int[row_count] evaluation order follows. */
  static final int RULE_ORDER_EXPLICIT = 1;

  private final ArtifactKind artifactKind;
  private final RuleSelectionPolicy ruleSelection;
  private final int columnCount;
  private final int rowCount;
  private final StringDictionaryReader dictionary;
  private final List<ColumnDefinition> columns;
  private final List<ColumnDecoder> decoders;
  private final int[] ruleOrder; // null = identity
  private final List<ColumnIndex> persistedIndexes; // null when the artifact carries none

  private BinaryArtifactReader(
      ArtifactKind artifactKind,
      RuleSelectionPolicy ruleSelection,
      int columnCount,
      int rowCount,
      StringDictionaryReader dictionary,
      List<ColumnDefinition> columns,
      List<ColumnDecoder> decoders,
      int[] ruleOrder,
      List<ColumnIndex> persistedIndexes) {
    this.artifactKind = artifactKind;
    this.ruleSelection = ruleSelection;
    this.columnCount = columnCount;
    this.rowCount = rowCount;
    this.dictionary = dictionary;
    this.columns = columns;
    this.decoders = decoders;
    this.ruleOrder = ruleOrder;
    this.persistedIndexes = persistedIndexes;
  }

  /** Parsed header fields, shared by both read paths. */
  private record Header(
      ArtifactKind kind,
      RuleSelectionPolicy selection,
      int columnCount,
      int rowCount,
      long dictionaryOffset,
      long columnsOffset,
      long dataOffset,
      long ruleOrderOffset,
      long indexOffset) {}

  private static Header readHeader(ByteBuffer buffer) {
    buffer.order(ByteOrder.BIG_ENDIAN);
    buffer.position(0);

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
    int kindOrdinal = buffer.get() & 0xFF;
    int selectionOrdinal = buffer.get() & 0xFF;
    buffer.getShort(); // reserved
    int columnCount = buffer.getInt();
    int rowCount = buffer.getInt();
    long dictionaryOffset = buffer.getLong();
    long columnsOffset = buffer.getLong();
    long dataOffset = buffer.getLong();
    long ruleOrderOffset = buffer.getLong();
    long indexOffset = buffer.getLong(); // zero in 4.0 artifacts (was reserved)

    return new Header(
        artifactKindFromOrdinal(kindOrdinal),
        ruleSelectionFromOrdinal(selectionOrdinal),
        columnCount,
        rowCount,
        dictionaryOffset,
        columnsOffset,
        dataOffset,
        ruleOrderOffset,
        indexOffset);
  }

  /**
   * Reads an artifact wholly contained in one buffer.
   *
   * @param buffer the buffer containing the artifact (must be in BIG_ENDIAN order)
   * @return the parsed artifact reader
   */
  static BinaryArtifactReader read(ByteBuffer buffer) {
    Header header = readHeader(buffer);

    StringDictionaryReader dictionary =
        StringDictionaryReader.read(buffer, checkedInt(header.dictionaryOffset(), "dictionary"));
    List<ColumnDefinition> columns =
        readColumnDefinitions(
            buffer,
            checkedInt(header.columnsOffset(), "columns"),
            header.columnCount(),
            dictionary);

    List<ColumnDecoder> decoders = new ArrayList<>(header.columnCount());
    for (ColumnDefinition col : columns) {
      int base = checkedInt(header.dataOffset() + col.dataOffset(), "column data");
      decoders.add(ColumnDecoder.create(col, buffer, base, header.rowCount(), dictionary));
    }

    int[] ruleOrder =
        readRuleOrder(
            buffer, checkedInt(header.ruleOrderOffset(), "rule order"), header.rowCount());

    List<ColumnIndex> persisted = null;
    if (header.indexOffset() > 0) {
      int directory = checkedInt(header.indexOffset(), "index directory");
      try {
        persisted =
            readPersistedIndexes(
                columns,
                c -> buffer.getLong(directory + c * 16),
                c -> buffer.getLong(directory + c * 16 + 8),
                (offset, length) -> buffer.slice(checkedInt(offset, "index block"), length));
      } catch (IOException e) {
        throw new LoadException("Failed to read persisted indexes: " + e.getMessage(), e);
      }
    }

    return new BinaryArtifactReader(
        header.kind(),
        header.selection(),
        header.columnCount(),
        header.rowCount(),
        dictionary,
        columns,
        List.copyOf(decoders),
        ruleOrder,
        persisted);
  }

  /**
   * Reads an artifact by memory-mapping the file section by section: one mapping for the metadata
   * region (header, dictionary, column definitions) and one per column's data slice. No mapping
   * spans more than one column, so the artifact itself may exceed 2 GB.
   *
   * @param channel an open channel on the artifact file (kept open by the caller for the mappings'
   *     lifetime)
   * @return the parsed artifact reader
   * @throws IOException if mapping fails
   */
  static BinaryArtifactReader readMapped(FileChannel channel) throws IOException {
    long fileSize = channel.size();
    if (fileSize < HEADER_SIZE) {
      throw new LoadException("Artifact file is truncated: " + fileSize + " bytes");
    }
    ByteBuffer headerBuf =
        channel.map(FileChannel.MapMode.READ_ONLY, 0, Math.min(fileSize, HEADER_SIZE));
    headerBuf.order(ByteOrder.BIG_ENDIAN);
    Header header = readHeader(headerBuf);

    if (header.dataOffset() > Integer.MAX_VALUE) {
      throw new LoadException("Metadata region exceeds 2GB: " + header.dataOffset());
    }
    // Metadata region: header + dictionary + column definitions in one mapping.
    ByteBuffer meta =
        channel
            .map(FileChannel.MapMode.READ_ONLY, 0, header.dataOffset())
            .order(ByteOrder.BIG_ENDIAN);
    StringDictionaryReader dictionary =
        StringDictionaryReader.read(meta, (int) header.dictionaryOffset());
    List<ColumnDefinition> columns =
        readColumnDefinitions(meta, (int) header.columnsOffset(), header.columnCount(), dictionary);

    // Per-column mappings. Definitions are written in data order, so each column's size is the
    // distance to the next column's offset (the last column ends at the rule-order section).
    List<ColumnDecoder> decoders = new ArrayList<>(header.columnCount());
    for (int c = 0; c < columns.size(); c++) {
      ColumnDefinition col = columns.get(c);
      long start = header.dataOffset() + col.dataOffset();
      long end =
          c + 1 < columns.size()
              ? header.dataOffset() + columns.get(c + 1).dataOffset()
              : header.ruleOrderOffset();
      long size = end - start;
      if (size < 0 || size > Integer.MAX_VALUE) {
        throw new LoadException("Column '" + col.name() + "' data size invalid: " + size);
      }
      ByteBuffer slice =
          channel.map(FileChannel.MapMode.READ_ONLY, start, size).order(ByteOrder.BIG_ENDIAN);
      decoders.add(ColumnDecoder.create(col, slice, 0, header.rowCount(), dictionary));
    }

    // Rule order section.
    long orderSize = fileSize - header.ruleOrderOffset();
    if (orderSize < 1) {
      throw new LoadException("Artifact is missing the rule order section");
    }
    ByteBuffer orderBuf =
        channel
            .map(
                FileChannel.MapMode.READ_ONLY,
                header.ruleOrderOffset(),
                Math.min(orderSize, 1L + 4L * header.rowCount()))
            .order(ByteOrder.BIG_ENDIAN);
    int[] ruleOrder = readRuleOrder(orderBuf, 0, header.rowCount());

    List<ColumnIndex> persisted = null;
    if (header.indexOffset() > 0) {
      ByteBuffer directory =
          channel
              .map(FileChannel.MapMode.READ_ONLY, header.indexOffset(), 16L * columns.size())
              .order(ByteOrder.BIG_ENDIAN);
      persisted =
          readPersistedIndexes(
              columns,
              c -> directory.getLong(c * 16),
              c -> directory.getLong(c * 16 + 8),
              (offset, length) ->
                  channel
                      .map(FileChannel.MapMode.READ_ONLY, offset, length)
                      .order(ByteOrder.BIG_ENDIAN));
    }

    return new BinaryArtifactReader(
        header.kind(),
        header.selection(),
        header.columnCount(),
        header.rowCount(),
        dictionary,
        columns,
        List.copyOf(decoders),
        ruleOrder,
        persisted);
  }

  /** Provides one index block's bytes, however the artifact is accessed. */
  @FunctionalInterface
  private interface BlockSource {
    ByteBuffer block(long offset, int length) throws IOException;
  }

  /** Reads a per-column value from the index directory. */
  @FunctionalInterface
  private interface DirectoryField {
    long get(int column);
  }

  /**
   * Constructs the persisted per-column indexes from the artifact's index directory. Entries with a
   * zero offset (non-indexable columns) stay null, matching the load-time builder's shape.
   */
  private static List<ColumnIndex> readPersistedIndexes(
      List<ColumnDefinition> columns,
      DirectoryField offsets,
      DirectoryField lengths,
      BlockSource blocks)
      throws IOException {
    List<ColumnIndex> indexes = new ArrayList<>(columns.size());
    for (int c = 0; c < columns.size(); c++) {
      long offset = offsets.get(c);
      if (offset <= 0) {
        indexes.add(null);
        continue;
      }
      long length = lengths.get(c);
      if (length <= 0 || length > Integer.MAX_VALUE) {
        throw new LoadException("Index block for column " + c + " has invalid length " + length);
      }
      ByteBuffer block = blocks.block(offset, (int) length);
      Operator op = columns.get(c).operator();
      ColumnIndex index =
          switch (op) {
            case BETWEEN_INCLUSIVE,
                    BETWEEN_EXCLUSIVE,
                    NOT_BETWEEN_INCLUSIVE,
                    NOT_BETWEEN_EXCLUSIVE ->
                RangeIntervalIndex.readFrom(block, op);
            default -> PostingListIndex.readFrom(block, op);
          };
      indexes.add(index);
    }
    return indexes;
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
      long columnDataOffset = buffer.getLong();
      int scale = buffer.getInt();
      buffer.getInt(); // reserved

      String name = dictionary.get(nameId);
      Operator operator = Operator.values()[operatorOrdinal];
      ColumnType type = ColumnType.values()[typeOrdinal];
      ColumnRole role = ColumnRole.fromOrdinal(roleOrdinal);

      columns.add(
          new ColumnDefinition(nameId, name, operator, type, role, flags, columnDataOffset, scale));
    }

    return List.copyOf(columns);
  }

  /**
   * Reads the evaluation-order sequence, or returns null for the identity permutation.
   *
   * <p>Order type 0 declares identity explicitly with no array. Type 1 carries an explicit
   * sequence, which is still collapsed to null if it happens to be the identity.
   */
  private static int[] readRuleOrder(ByteBuffer buffer, int offset, int rowCount) {
    int orderType = buffer.get(offset) & 0xFF;
    if (orderType == RULE_ORDER_IDENTITY) {
      return null;
    }
    if (orderType != RULE_ORDER_EXPLICIT) {
      throw new LoadException("Unknown rule order type: " + orderType);
    }
    int pos = offset + 1;
    boolean identity = true;
    for (int i = 0; i < rowCount; i++) {
      if (buffer.getInt(pos + i * 4) != i) {
        identity = false;
        break;
      }
    }
    if (identity) {
      return null;
    }
    int[] order = new int[rowCount];
    for (int i = 0; i < rowCount; i++) {
      order[i] = buffer.getInt(pos + i * 4);
    }
    return order;
  }

  private static int checkedInt(long value, String what) {
    if (value < 0 || value > Integer.MAX_VALUE) {
      throw new LoadException(
          "Artifact " + what + " offset " + value + " requires a file-mapped load (over 2GB)");
    }
    return (int) value;
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

  StringDictionaryReader dictionary() {
    return dictionary;
  }

  List<ColumnDefinition> columns() {
    return columns;
  }

  List<ColumnDecoder> decoders() {
    return decoders;
  }

  /** Evaluation-order sequence over physical rows, or null for the identity permutation. */
  int[] ruleOrder() {
    return ruleOrder;
  }

  /**
   * Per-column indexes persisted in the artifact (positional with columns, nulls for non-indexable
   * columns), or null when the artifact carries none.
   */
  List<ColumnIndex> persistedIndexes() {
    return persistedIndexes;
  }
}
