package in.systemhalted.kisoku.runtime.codec;

import in.systemhalted.kisoku.api.ColumnType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Reduces each {@link ColumnType} to the order-preserving {@code long} domain of {@link
 * ComparableCodes}.
 *
 * <p>The mapping is chosen so that comparing two codes is the same as comparing the two values:
 *
 * <ul>
 *   <li>{@code INTEGER} - the value itself (64-bit, so callers passing a {@code Long} are not
 *       silently narrowed)
 *   <li>{@code DECIMAL} - the unscaled value at the column's scale, so {@code 0.50} and {@code 0.5}
 *       are the same code and {@code 1000.00} outranks {@code 100.00}
 *   <li>{@code DATE} - epoch day
 *   <li>{@code TIMESTAMP} - epoch microseconds
 *   <li>{@code BOOLEAN} - 0 or 1
 *   <li>{@code STRING} - the value's rank in the sorted dictionary, resolved by the caller
 * </ul>
 *
 * <p>Values that a column cannot represent exactly (a decimal with more precision than the column's
 * scale, a timestamp with sub-microsecond precision) are encoded with {@link
 * ComparableCodes#between(long)} rather than rounded, so ordering stays exact and equality stays
 * false.
 */
public final class ValueCodec {
  private ValueCodec() {}

  /** Microseconds per second, for TIMESTAMP encoding. */
  private static final long MICROS_PER_SECOND = 1_000_000L;

  /**
   * Encodes a decimal at a fixed column scale.
   *
   * @param value the decimal value
   * @param scale the column's decimal scale
   * @return the order-preserving code
   */
  public static long encodeDecimal(BigDecimal value, int scale) {
    BigDecimal rescaled = value.setScale(scale, RoundingMode.FLOOR);
    long floorUnscaled = clampToLong(rescaled.unscaledValue());
    // FLOOR is exact only when nothing was discarded; otherwise the value sits in the gap above.
    boolean exact = rescaled.compareTo(value) == 0;
    return exact ? ComparableCodes.exact(floorUnscaled) : ComparableCodes.between(floorUnscaled);
  }

  /**
   * Decodes a value encoded by {@link #encodeDecimal(BigDecimal, int)}.
   *
   * @param code the stored code
   * @param scale the column's decimal scale
   * @return the decimal value
   */
  public static BigDecimal decodeDecimal(long code, int scale) {
    return BigDecimal.valueOf(ComparableCodes.decodeExact(code), scale);
  }

  /**
   * Encodes an instant as epoch microseconds.
   *
   * @param value the instant
   * @return the order-preserving code
   */
  public static long encodeTimestamp(Instant value) {
    long micros =
        Math.addExact(
            Math.multiplyExact(value.getEpochSecond(), MICROS_PER_SECOND), value.getNano() / 1000);
    boolean exact = value.getNano() % 1000 == 0;
    return exact ? ComparableCodes.exact(micros) : ComparableCodes.between(micros);
  }

  /**
   * Decodes a value encoded by {@link #encodeTimestamp(Instant)}.
   *
   * @param code the stored code
   * @return the instant
   */
  public static Instant decodeTimestamp(long code) {
    long micros = ComparableCodes.decodeExact(code);
    return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
  }

  /**
   * Encodes a date as epoch days.
   *
   * @param value the date
   * @return the order-preserving code
   */
  public static long encodeDate(LocalDate value) {
    return ComparableCodes.exact(value.toEpochDay());
  }

  /**
   * Decodes a value encoded by {@link #encodeDate(LocalDate)}.
   *
   * @param code the stored code
   * @return the date
   */
  public static LocalDate decodeDate(long code) {
    return LocalDate.ofEpochDay(ComparableCodes.decodeExact(code));
  }

  /**
   * Encodes a 64-bit integer.
   *
   * @param value the integer value
   * @return the order-preserving code
   */
  public static long encodeInteger(long value) {
    return ComparableCodes.exact(value);
  }

  /**
   * Decodes a value encoded by {@link #encodeInteger(long)}.
   *
   * @param code the stored code
   * @return the integer value
   */
  public static long decodeInteger(long code) {
    return ComparableCodes.decodeExact(code);
  }

  /**
   * Encodes a boolean.
   *
   * @param value the boolean value
   * @return the order-preserving code
   */
  public static long encodeBoolean(boolean value) {
    return ComparableCodes.exact(value ? 1 : 0);
  }

  /**
   * Decodes a value encoded by {@link #encodeBoolean(boolean)}.
   *
   * @param code the stored code
   * @return the boolean value
   */
  public static boolean decodeBoolean(long code) {
    return ComparableCodes.decodeExact(code) != 0;
  }

  /**
   * The decimal scale needed to represent a value exactly, or 0 for non-decimal text.
   *
   * @param text the cell text
   * @return the scale, never negative
   */
  public static int scaleOf(String text) {
    return Math.max(0, new BigDecimal(text.trim()).scale());
  }

  private static long clampToLong(java.math.BigInteger value) {
    if (value.bitLength() >= 64) {
      return value.signum() > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
    return value.longValueExact();
  }
}
