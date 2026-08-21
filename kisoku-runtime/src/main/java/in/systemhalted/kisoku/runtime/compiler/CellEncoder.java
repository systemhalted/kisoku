package in.systemhalted.kisoku.runtime.compiler;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.compilation.CompilationException;
import in.systemhalted.kisoku.runtime.codec.ValueCodec;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Encodes one CSV cell operand into the order-preserving code domain.
 *
 * <p>Stateless per cell, so the compiler can stream rows without holding any of them: each cell is
 * encoded as it is read and appended to its column's temporary stream.
 */
final class CellEncoder {
  private CellEncoder() {}

  /** Widest set a row can hold, bounded by the 16-bit length field. */
  static final int MAX_SET_SIZE = 0xFFFF;

  /**
   * Encodes a scalar operand (already trimmed, non-empty).
   *
   * @param text the operand text
   * @param type the column type
   * @param scale the column's decimal scale (DECIMAL only)
   * @param dictionary the string dictionary
   * @return the order-preserving code
   */
  static long scalarCode(String text, ColumnType type, int scale, StringDictionary dictionary) {
    try {
      return switch (type) {
        case STRING -> dictionary.codeFor(text);
        case INTEGER -> ValueCodec.encodeInteger(Long.parseLong(text));
        case DECIMAL -> ValueCodec.encodeDecimal(new BigDecimal(text), scale);
        case BOOLEAN -> ValueCodec.encodeBoolean(parseBoolean(text));
        case DATE -> ValueCodec.encodeDate(LocalDate.parse(text));
        case TIMESTAMP -> ValueCodec.encodeTimestamp(parseTimestamp(text));
      };
    } catch (NumberFormatException | DateTimeParseException e) {
      throw new CompilationException(
          "Value '" + text + "' is not a valid " + type + ": " + e.getMessage(), e);
    }
  }

  /**
   * Encodes a range cell in {@code (min,max)} format.
   *
   * @param cell the cell text (non-blank)
   * @param type the column type
   * @param scale the column's decimal scale
   * @param dictionary the string dictionary
   * @return two codes, [min, max]
   */
  static long[] rangeCodes(String cell, ColumnType type, int scale, StringDictionary dictionary) {
    String trimmed = cell.trim();
    if (!trimmed.startsWith("(") || !trimmed.endsWith(")")) {
      throw new CompilationException("Range must be in (min,max) format: " + cell);
    }
    String inner = trimmed.substring(1, trimmed.length() - 1);
    String[] parts = inner.split(",", 2);
    if (parts.length != 2) {
      throw new CompilationException("Range must have exactly two parts: " + cell);
    }
    return new long[] {
      scalarCode(parts[0].trim(), type, scale, dictionary),
      scalarCode(parts[1].trim(), type, scale, dictionary)
    };
  }

  /**
   * Encodes a set cell in {@code (a,b,c)} format.
   *
   * @param cell the cell text (non-blank)
   * @param type the column type
   * @param scale the column's decimal scale
   * @param dictionary the string dictionary
   * @return the member codes
   */
  static long[] setCodes(String cell, ColumnType type, int scale, StringDictionary dictionary) {
    String trimmed = cell.trim();
    if (!trimmed.startsWith("(") || !trimmed.endsWith(")")) {
      throw new CompilationException("Set must be in (a,b,c) format: " + cell);
    }
    String inner = trimmed.substring(1, trimmed.length() - 1);
    if (inner.isEmpty()) {
      return new long[0];
    }
    String[] parts = inner.split(",");
    if (parts.length > MAX_SET_SIZE) {
      throw new CompilationException(
          "Set has " + parts.length + " members, which exceeds the limit of " + MAX_SET_SIZE);
    }
    long[] codes = new long[parts.length];
    for (int i = 0; i < parts.length; i++) {
      codes[i] = scalarCode(parts[i].trim(), type, scale, dictionary);
    }
    return codes;
  }

  private static boolean parseBoolean(String value) {
    String lower = value.toLowerCase();
    return "true".equals(lower) || "1".equals(lower) || "yes".equals(lower);
  }

  private static Instant parseTimestamp(String value) {
    Instant instant = Instant.parse(value);
    if (instant.getNano() % 1000 != 0) {
      throw new CompilationException(
          "Timestamp '"
              + value
              + "' has sub-microsecond precision, which the artifact cannot store");
    }
    return instant;
  }
}
