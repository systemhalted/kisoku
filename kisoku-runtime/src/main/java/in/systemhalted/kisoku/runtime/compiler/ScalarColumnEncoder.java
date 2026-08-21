package in.systemhalted.kisoku.runtime.compiler;

import in.systemhalted.kisoku.api.ColumnType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

/**
 * Encodes scalar column data (single value per row).
 *
 * <p>Handles operators: RULE_ID, PRIORITY, SET, EQ, NE, GT, GTE, LT, LTE
 *
 * <p>Format:
 *
 * <pre>
 * presence_bitmap (ceil(row_count/8) bytes)
 * values[row_count] (8 bytes each)
 * </pre>
 */
final class ScalarColumnEncoder extends ColumnEncoder {

  ScalarColumnEncoder(StringDictionary dictionary, ColumnType columnType, int scale) {
    super(dictionary, columnType, scale);
  }

  @Override
  byte[] encode(List<String[]> rows, int columnIndex) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = createDataOutputStream(baos);

    // Write presence bitmap
    byte[] bitmap = buildPresenceBitmap(rows, columnIndex);
    writeOrThrow(dos, d -> d.write(bitmap));

    // Write values
    for (String[] row : rows) {
      long encoded = encodeCell(row[columnIndex]);
      writeOrThrow(dos, d -> d.writeLong(encoded));
    }

    return baos.toByteArray();
  }
}
