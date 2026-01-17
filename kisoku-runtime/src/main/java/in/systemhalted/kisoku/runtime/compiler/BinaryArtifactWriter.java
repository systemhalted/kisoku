package in.systemhalted.kisoku.runtime.compiler;

import in.systemhalted.kisoku.api.ArtifactKind;
import in.systemhalted.kisoku.api.evaluation.RuleSelectionPolicy;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Writes the compiled ruleset binary artifact format.
 *
 * <p>Format layout:
 *
 * <pre>
 * Header (32 bytes)
 * String Dictionary
 * Column Definitions
 * Rule Data (columnar)
 * Rule Order Index
 * </pre>
 */
final class BinaryArtifactWriter {
  /** Magic bytes: "KISS" (0x4B495353) */
  static final int MAGIC = 0x4B495353;

  static final short VERSION_MAJOR = 1;
  static final short VERSION_MINOR = 0;

  private static final int HEADER_SIZE = 32;

  /**
   * Writes the complete binary artifact.
   *
   * @param artifactKind PRODUCTION or TEST_INCLUSIVE
   * @param ruleSelectionPolicy rule selection mode
   * @param columnCount number of columns
   * @param rowCount number of rules
   * @param dictionaryBytes serialized string dictionary
   * @param columnDefinitionsBytes serialized column definitions
   * @param ruleDataBytes columnar encoded rule data
   * @param ruleOrderBytes rule order index
   * @return the complete binary artifact
   */
  byte[] write(
      ArtifactKind artifactKind,
      RuleSelectionPolicy ruleSelectionPolicy,
      int columnCount,
      int rowCount,
      byte[] dictionaryBytes,
      byte[] columnDefinitionsBytes,
      byte[] ruleDataBytes,
      byte[] ruleOrderBytes) {

    // Calculate offsets
    int dictionaryOffset = HEADER_SIZE;
    int columnsOffset = dictionaryOffset + dictionaryBytes.length;
    int dataOffset = columnsOffset + columnDefinitionsBytes.length;

    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      DataOutputStream dos = new DataOutputStream(baos);

      // Header (32 bytes)
      dos.writeInt(MAGIC); // 0-3: magic
      dos.writeShort(VERSION_MAJOR); // 4-5: version_major
      dos.writeShort(VERSION_MINOR); // 6-7: version_minor
      dos.writeByte(artifactKindOrdinal(artifactKind)); // 8: artifact_kind
      dos.writeByte(ruleSelectionOrdinal(ruleSelectionPolicy)); // 9: rule_selection
      dos.writeShort(0); // 10-11: reserved
      dos.writeInt(columnCount); // 12-15: column_count
      dos.writeInt(rowCount); // 16-19: row_count
      dos.writeInt(dictionaryOffset); // 20-23: dictionary_offset
      dos.writeInt(columnsOffset); // 24-27: columns_offset
      dos.writeInt(dataOffset); // 28-31: data_offset

      // String Dictionary
      dos.write(dictionaryBytes);

      // Column Definitions
      dos.write(columnDefinitionsBytes);

      // Rule Data
      dos.write(ruleDataBytes);

      // Rule Order Index
      dos.write(ruleOrderBytes);

      dos.flush();
      return baos.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to write artifact", e);
    }
  }

  /**
   * Writes a single column definition.
   *
   * @param nameId dictionary ID of column name
   * @param operatorOrdinal operator enum ordinal
   * @param typeOrdinal column type ordinal
   * @param roleOrdinal column role (0=INPUT, 1=OUTPUT, 2=METADATA)
   * @param flags bit flags (0x01=nullable, 0x02=test-only)
   * @param dataOffset byte offset within rule data section
   * @return serialized column definition (12 bytes)
   */
  static byte[] writeColumnDefinition(
      int nameId,
      int operatorOrdinal,
      int typeOrdinal,
      int roleOrdinal,
      int flags,
      int dataOffset) {
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream(12);
      DataOutputStream dos = new DataOutputStream(baos);

      dos.writeInt(nameId); // 0-3: name_id
      dos.writeByte(operatorOrdinal); // 4: operator
      dos.writeByte(typeOrdinal); // 5: column_type
      dos.writeByte(roleOrdinal); // 6: column_role
      dos.writeByte(flags); // 7: flags
      dos.writeInt(dataOffset); // 8-11: data_offset

      dos.flush();
      return baos.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to write column definition", e);
    }
  }

  /**
   * Writes the rule order index.
   *
   * @param orderType 0=insertion order, 1=priority order
   * @param ruleIndices row indices in evaluation order
   * @return serialized rule order index
   */
  static byte[] writeRuleOrderIndex(int orderType, List<Integer> ruleIndices) {
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      DataOutputStream dos = new DataOutputStream(baos);

      dos.writeByte(orderType);
      for (int index : ruleIndices) {
        dos.writeInt(index);
      }

      dos.flush();
      return baos.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to write rule order", e);
    }
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
