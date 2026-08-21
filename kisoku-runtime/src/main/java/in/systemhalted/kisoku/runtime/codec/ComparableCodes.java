package in.systemhalted.kisoku.runtime.codec;

/**
 * The order-preserving integer domain that both compilation and evaluation encode values into.
 *
 * <p>Every column type is reduced to a {@code long} whose natural order matches the value's own
 * order, so a single comparison implements {@code GT}/{@code GTE}/{@code LT}/{@code LTE} and the
 * range operators for strings, decimals, dates and timestamps alike.
 *
 * <p>Codes are <em>doubled</em>: an exactly representable value {@code v} encodes to {@code 2v},
 * which leaves every odd number free to represent a position strictly between two representable
 * values. That matters for inputs the compiler never saw — a string absent from the dictionary, or
 * a decimal with more precision than its column stores. Such an input encodes as {@link
 * #between(long)} of the greatest representable value below it, so it compares correctly against
 * every stored value while never comparing equal to any of them.
 *
 * <p>{@link #NULL_CODE} (0) is reserved: it is the code for a null or empty stored cell. Absence of
 * an input is <em>not</em> represented here — it is carried separately, because an absent field and
 * a supplied-but-unrepresentable value must behave differently.
 */
public final class ComparableCodes {
  private ComparableCodes() {}

  /** Code for a null or empty stored value. */
  public static final long NULL_CODE = 0L;

  /** Largest value that {@link #exact(long)} can encode without overflow. */
  public static final long MAX_EXACT = Long.MAX_VALUE / 2;

  /** Smallest value that {@link #exact(long)} can encode without overflow. */
  public static final long MIN_EXACT = Long.MIN_VALUE / 2;

  /**
   * Encodes a value that the column represents exactly.
   *
   * @param value the value in the type's own integral domain (epoch day, unscaled decimal, ...)
   * @return the order-preserving code, saturated at the domain bounds
   */
  public static long exact(long value) {
    if (value > MAX_EXACT) {
      return Long.MAX_VALUE;
    }
    if (value < MIN_EXACT) {
      return Long.MIN_VALUE;
    }
    return value * 2;
  }

  /**
   * Encodes a position strictly between {@code floor} and {@code floor + 1}.
   *
   * <p>Used for an input the column cannot represent exactly: it orders above every stored value
   * less than or equal to {@code floor} and below every stored value at or above {@code floor + 1},
   * and equals none of them.
   *
   * @param floor the greatest exactly representable value below the input
   * @return the order-preserving code for the gap above {@code floor}
   */
  public static long between(long floor) {
    if (floor >= MAX_EXACT) {
      return Long.MAX_VALUE;
    }
    if (floor < MIN_EXACT) {
      return Long.MIN_VALUE;
    }
    return floor * 2 + 1;
  }

  /**
   * Recovers the value encoded by {@link #exact(long)}.
   *
   * @param code a code produced by {@link #exact(long)}
   * @return the original value
   */
  public static long decodeExact(long code) {
    return code / 2;
  }
}
