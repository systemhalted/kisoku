package in.systemhalted.kisoku.runtime.loader;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Decodes inline UTF-8 string column data ({@code RULE_ID}).
 *
 * <p>Format (within the column's slice of the artifact buffer):
 *
 * <pre>
 * presence_bitmap (ceil(row_count/8) bytes)
 * byte_offsets[row_count + 1] (4 bytes each)
 * utf8_blob
 * </pre>
 *
 * <p>Values stay in the (possibly memory-mapped) buffer; {@link #getValue(int)} decodes one row's
 * bytes on demand. {@code RULE_ID} is metadata - it is never matched against an input - so the
 * match methods are unsupported by contract: the evaluator only consults input columns.
 */
final class InlineStringColumnDecoder implements ColumnDecoder {
  private final ByteBuffer buffer;
  private final int bitmapBase;
  private final int offsetsBase;
  private final int blobBase;

  private InlineStringColumnDecoder(
      ByteBuffer buffer, int bitmapBase, int offsetsBase, int blobBase) {
    this.buffer = buffer;
    this.bitmapBase = bitmapBase;
    this.offsetsBase = offsetsBase;
    this.blobBase = blobBase;
  }

  /**
   * Creates a decoder over the column's data located at an absolute byte offset in the buffer.
   *
   * @param buffer the artifact buffer
   * @param base absolute byte offset of this column's data
   * @param rowCount number of rows
   * @return the decoder
   */
  static InlineStringColumnDecoder create(ByteBuffer buffer, int base, int rowCount) {
    int offsetsBase = base + BitMapUtils.bitmapSize(rowCount);
    int blobBase = offsetsBase + (rowCount + 1) * 4;
    return new InlineStringColumnDecoder(buffer, base, offsetsBase, blobBase);
  }

  @Override
  public boolean matches(int rowIndex, Object inputValue) {
    throw new UnsupportedOperationException("Inline string columns are metadata, never matched");
  }

  @Override
  public boolean matchesCoerced(int rowIndex, long coercedValue, boolean present) {
    throw new UnsupportedOperationException("Inline string columns are metadata, never matched");
  }

  @Override
  public boolean hasCondition(int rowIndex) {
    return BitMapUtils.isPresent(buffer, bitmapBase, rowIndex);
  }

  @Override
  public Object getValue(int rowIndex) {
    if (!hasCondition(rowIndex)) {
      return null;
    }
    int start = buffer.getInt(offsetsBase + rowIndex * 4);
    int end = buffer.getInt(offsetsBase + (rowIndex + 1) * 4);
    byte[] bytes = new byte[end - start];
    // Absolute bulk get keeps the shared buffer position untouched for concurrent readers.
    buffer.get(blobBase + start, bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
