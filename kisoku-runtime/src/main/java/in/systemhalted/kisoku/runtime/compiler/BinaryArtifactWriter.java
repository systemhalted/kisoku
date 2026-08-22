package in.systemhalted.kisoku.runtime.compiler;

import in.systemhalted.kisoku.api.ArtifactKind;
import in.systemhalted.kisoku.api.evaluation.RuleSelectionPolicy;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Layout authority for the binary artifact format, written as a stream.
 *
 * <p>Format layout (all offsets 64-bit, so artifacts may exceed 2 GB):
 *
 * <pre>
 * Header (64 bytes)
 * String Dictionary
 * Column Definitions (24 bytes each)
 * Rule Data (columnar; per-column data must each stay under 2 GB)
 * Rule Order (1-byte order_type; 0 = identity, nothing follows)
 * </pre>
 *
 * <p>The writer emits sections sequentially to a {@link DataOutputStream} - nothing is buffered
 * whole - so compilation memory does not scale with the artifact. All section offsets are computed
 * up front from known sizes and written into the header first.
 */
final class BinaryArtifactWriter {
  /** Magic bytes: "KISS" (0x4B495353) */
  static final int MAGIC = 0x4B495353;

  /**
   * Major version 4 moves every section offset (header fields and per-column {@code data_offset})
   * to 64 bits and adds an explicit {@code rule_order_offset}, so artifacts can exceed 2 GB. Column
   * definitions grew from 16 to 24 bytes. The rule-order section stores a 1-byte order type; 0
   * means identity with no array following. Not readable by earlier majors.
   */
  static final short VERSION_MAJOR = 4;

  /**
   * Minor version 1 adds an {@code index_offset} header field (bytes 52-59, zero in 4.0 artifacts)
   * pointing at a per-column index directory, so candidate indexes built at compile time are
   * memory-mapped at load instead of rebuilt. Backward and forward compatible within major 4: a 4.0
   * reader ignores the trailing index section, and a 4.1 reader treats a zero offset as "no
   * persisted indexes" and builds them at load.
   */
  static final short VERSION_MINOR = 1;

  /** Bytes per index-directory entry: block offset (8) + block length (8); zeros = no index. */
  static final int INDEX_DIRECTORY_ENTRY_SIZE = 16;

  static final int HEADER_SIZE = 64;

  /** Serialized size of one column definition. */
  static final int COLUMN_DEFINITION_SIZE = 24;

  /** Rule-order type: rows are stored in evaluation order; no explicit array follows. */
  static final int RULE_ORDER_IDENTITY = 0;

  /** Rule-order type: an explicit int[row_count] evaluation order follows. */
  static final int RULE_ORDER_EXPLICIT = 1;

  private BinaryArtifactWriter() {}

  /**
   * Writes the 64-byte header.
   *
   * @param dos the artifact stream, positioned at 0
   * @param artifactKind PRODUCTION or TEST_INCLUSIVE
   * @param ruleSelection rule selection mode
   * @param columnCount number of columns
   * @param rowCount number of rules
   * @param dictionaryOffset byte offset of the dictionary section
   * @param columnsOffset byte offset of the column definitions section
   * @param dataOffset byte offset of the rule data section
   * @param ruleOrderOffset byte offset of the rule order section
   * @param indexOffset byte offset of the index directory, or 0 when indexes are not persisted
   * @throws IOException if the stream fails
   */
  static void writeHeader(
      DataOutputStream dos,
      ArtifactKind artifactKind,
      RuleSelectionPolicy ruleSelection,
      int columnCount,
      int rowCount,
      long dictionaryOffset,
      long columnsOffset,
      long dataOffset,
      long ruleOrderOffset,
      long indexOffset)
      throws IOException {
    dos.writeInt(MAGIC); // 0-3: magic
    dos.writeShort(VERSION_MAJOR); // 4-5: version_major
    dos.writeShort(VERSION_MINOR); // 6-7: version_minor
    dos.writeByte(artifactKindOrdinal(artifactKind)); // 8: artifact_kind
    dos.writeByte(ruleSelectionOrdinal(ruleSelection)); // 9: rule_selection
    dos.writeShort(0); // 10-11: reserved
    dos.writeInt(columnCount); // 12-15: column_count
    dos.writeInt(rowCount); // 16-19: row_count
    dos.writeLong(dictionaryOffset); // 20-27: dictionary_offset
    dos.writeLong(columnsOffset); // 28-35: columns_offset
    dos.writeLong(dataOffset); // 36-43: data_offset
    dos.writeLong(ruleOrderOffset); // 44-51: rule_order_offset
    dos.writeLong(indexOffset); // 52-59: index_offset (0 = no persisted indexes)
    dos.writeInt(0); // 60-63: reserved
  }

  /**
   * Writes a single column definition (24 bytes).
   *
   * @param dos the artifact stream
   * @param nameId dictionary ID of column name
   * @param operatorOrdinal operator enum ordinal
   * @param typeOrdinal column type ordinal
   * @param roleOrdinal column role (0=INPUT, 1=OUTPUT, 2=METADATA)
   * @param flags bit flags (0x01=nullable, 0x02=test-only)
   * @param dataOffset byte offset within rule data section
   * @param scale decimal scale for DECIMAL columns, 0 otherwise
   * @throws IOException if the stream fails
   */
  static void writeColumnDefinition(
      DataOutputStream dos,
      int nameId,
      int operatorOrdinal,
      int typeOrdinal,
      int roleOrdinal,
      int flags,
      long dataOffset,
      int scale)
      throws IOException {
    dos.writeInt(nameId); // 0-3: name_id
    dos.writeByte(operatorOrdinal); // 4: operator
    dos.writeByte(typeOrdinal); // 5: column_type
    dos.writeByte(roleOrdinal); // 6: column_role
    dos.writeByte(flags); // 7: flags
    dos.writeLong(dataOffset); // 8-15: data_offset
    dos.writeInt(scale); // 16-19: decimal scale
    dos.writeInt(0); // 20-23: reserved
  }

  private static int artifactKindOrdinal(ArtifactKind kind) {
    return switch (kind) {
      case PRODUCTION -> 0;
      case TEST_INCLUSIVE -> 1;
    };
  }

  private static int ruleSelectionOrdinal(RuleSelectionPolicy policy) {
    return switch (policy) {
      case AUTO -> 0;
      case PRIORITY -> 1;
      case FIRST_MATCH -> 2;
    };
  }
}
