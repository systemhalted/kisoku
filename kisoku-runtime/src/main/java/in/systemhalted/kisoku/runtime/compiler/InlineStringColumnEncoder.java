package in.systemhalted.kisoku.runtime.compiler;

import in.systemhalted.kisoku.api.ColumnType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Encodes a column's cells as inline UTF-8 strings, bypassing the dictionary.
 *
 * <p>Used for {@code RULE_ID}: its values are unique per row by design, so dictionary-encoding them
 * costs heap linear in the row count at load time, for a value that is never matched against an
 * input and only ever decoded for the single winning row of an evaluation. Storing the bytes inline
 * keeps them in the (memory-mapped) artifact until that one decode.
 *
 * <p>Format:
 *
 * <pre>
 * presence_bitmap (ceil(row_count/8) bytes)
 * byte_offsets[row_count + 1] (4 bytes each)  - offsets into the blob; entry [i+1]-[i] is row i's
 *                                               length, entry [row_count] is the blob size
 * utf8_blob (byte_offsets[row_count] bytes)
 * </pre>
 */
final class InlineStringColumnEncoder extends ColumnEncoder {

  InlineStringColumnEncoder(StringDictionary dictionary, ColumnType columnType) {
    super(dictionary, columnType, 0);
  }

  @Override
  byte[] encode(List<String[]> rows, int columnIndex) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = createDataOutputStream(baos);

    byte[] bitmap = buildPresenceBitmap(rows, columnIndex);
    writeOrThrow(dos, d -> d.write(bitmap));

    byte[][] encoded = new byte[rows.size()][];
    for (int i = 0; i < rows.size(); i++) {
      String value = rows.get(i)[columnIndex];
      encoded[i] = isPresent(value) ? value.trim().getBytes(StandardCharsets.UTF_8) : new byte[0];
    }

    int offset = 0;
    for (byte[] bytes : encoded) {
      int current = offset;
      writeOrThrow(dos, d -> d.writeInt(current));
      offset += bytes.length;
    }
    int blobSize = offset;
    writeOrThrow(dos, d -> d.writeInt(blobSize));

    for (byte[] bytes : encoded) {
      writeOrThrow(dos, d -> d.write(bytes));
    }

    return baos.toByteArray();
  }
}
