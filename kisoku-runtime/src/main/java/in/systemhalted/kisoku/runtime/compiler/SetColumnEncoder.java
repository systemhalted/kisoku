package in.systemhalted.kisoku.runtime.compiler;

import in.systemhalted.kisoku.api.ColumnType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes set column data (multiple values per row).
 *
 * <p>Handles operators: IN, NOT_IN
 *
 * <p>Format:
 *
 * <pre>
 * presence_bitmap (ceil(row_count/8) bytes)
 * list_offsets[row_count] (4 bytes each)
 * list_lengths[row_count] (2 bytes each)
 * all_values[] (4 bytes each)
 * </pre>
 */
final class SetColumnEncoder extends ColumnEncoder {

  SetColumnEncoder(StringDictionary dictionary, ColumnType columnType) {
    super(dictionary, columnType);
  }

  @Override
  byte[] encode(List<String[]> rows, int columnIndex) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = createDataOutputStream(baos);

    // Write presence bitmap
    byte[] bitmap = buildPresenceBitmap(rows, columnIndex);
    writeOrThrow(dos, d -> d.write(bitmap));

    // Parse all sets and collect values
    List<int[]> parsedSets = new ArrayList<>();
    List<Integer> allValues = new ArrayList<>();

    for (String[] row : rows) {
      String value = row[columnIndex];
      int[] setValues = parseSet(value);
      parsedSets.add(setValues);
    }

    // Calculate offsets and build all_values array
    int[] offsets = new int[rows.size()];
    short[] lengths = new short[rows.size()];
    int currentOffset = 0;

    for (int i = 0; i < parsedSets.size(); i++) {
      int[] setValues = parsedSets.get(i);
      offsets[i] = currentOffset;
      lengths[i] = (short) setValues.length;
      for (int v : setValues) {
        allValues.add(v);
      }
      currentOffset += setValues.length;
    }

    // Write list_offsets
    for (int offset : offsets) {
      writeOrThrow(dos, d -> d.writeInt(offset));
    }

    // Write list_lengths
    for (short length : lengths) {
      writeOrThrow(dos, d -> d.writeShort(length));
    }

    // Write all_values
    for (int v : allValues) {
      writeOrThrow(dos, d -> d.writeInt(v));
    }

    return baos.toByteArray();
  }

  /**
   * Parses a set value in (a,b,c) format.
   *
   * @param value the cell value, e.g., "(APAC,EMEA)"
   * @return int array of encoded values, or empty array if blank
   */
  private int[] parseSet(String value) {
    if (!isPresent(value)) {
      return new int[0];
    }

    String trimmed = value.trim();
    if (!trimmed.startsWith("(") || !trimmed.endsWith(")")) {
      throw new IllegalArgumentException("Set must be in (a,b,c) format: " + value);
    }

    String inner = trimmed.substring(1, trimmed.length() - 1);
    if (inner.isEmpty()) {
      return new int[0];
    }

    String[] parts = inner.split(",");
    int[] result = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      result[i] = encodeSetValue(parts[i].trim());
    }
    return result;
  }

  private int encodeSetValue(String value) {
    return switch (columnType) {
      case STRING -> dictionary.getId(value);
      case INTEGER -> parseInteger(value);
      case DECIMAL -> dictionary.getId(value);
      default -> throw new IllegalArgumentException("Unsupported set type: " + columnType);
    };
  }
}
