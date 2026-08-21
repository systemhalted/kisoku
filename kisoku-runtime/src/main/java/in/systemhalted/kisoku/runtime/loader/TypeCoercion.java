package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.evaluation.EvaluationException;
import in.systemhalted.kisoku.runtime.codec.ComparableCodes;
import in.systemhalted.kisoku.runtime.codec.ValueCodec;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Converts DecisionInput values into the order-preserving code domain used by the artifact. */
final class TypeCoercion {
  private TypeCoercion() {}

  /**
   * Convert an input value to its order-preserving comparable code.
   *
   * <p>The result is directly comparable to a stored code by any operator, ordering operators
   * included, because both sides use the encoding described by {@link ComparableCodes}.
   *
   * <p>A {@code null} value yields {@link ComparableCodes#NULL_CODE}, but callers must not read
   * that as "absent": absence is tracked separately, since an omitted field and a supplied value
   * that the column cannot represent must behave differently.
   *
   * @param value the input value from DecisionInput
   * @param type the column type
   * @param scale the column's decimal scale (DECIMAL only)
   * @param dictionary the string dictionary for lookup
   * @return the comparable code
   */
  static long toComparableCode(
      Object value, ColumnType type, int scale, StringDictionaryReader dictionary) {
    if (value == null) {
      return ComparableCodes.NULL_CODE;
    }

    return switch (type) {
      case STRING -> dictionary.codeFor(value.toString());
      case INTEGER -> {
        if (value instanceof Number n) {
          yield ValueCodec.encodeInteger(n.longValue());
        }
        throw new EvaluationException("Expected Integer, got: " + value.getClass().getName());
      }
      case DECIMAL -> ValueCodec.encodeDecimal(toBigDecimal(value), scale);
      case BOOLEAN -> {
        if (value instanceof Boolean b) {
          yield ValueCodec.encodeBoolean(b);
        }
        throw new EvaluationException("Expected Boolean, got: " + value.getClass().getName());
      }
      case DATE -> {
        if (value instanceof LocalDate ld) {
          yield ValueCodec.encodeDate(ld);
        }
        throw new EvaluationException("Expected LocalDate, got: " + value.getClass().getName());
      }
      case TIMESTAMP -> {
        if (value instanceof Instant instant) {
          yield ValueCodec.encodeTimestamp(instant);
        }
        throw new EvaluationException("Expected Instant, got: " + value.getClass().getName());
      }
    };
  }

  /**
   * Decode a stored code back to a Java object for output.
   *
   * @param code the stored code
   * @param type the column type
   * @param scale the column's decimal scale (DECIMAL only)
   * @param dictionary the string dictionary
   * @return the decoded Java object
   */
  static Object decodeValue(
      long code, ColumnType type, int scale, StringDictionaryReader dictionary) {
    if (code == ComparableCodes.NULL_CODE
        && type != ColumnType.INTEGER
        && type != ColumnType.BOOLEAN) {
      return null;
    }

    return switch (type) {
      case STRING -> dictionary.get((int) ComparableCodes.decodeExact(code));
      case INTEGER -> ValueCodec.decodeInteger(code);
      case DECIMAL -> ValueCodec.decodeDecimal(code, scale);
      case BOOLEAN -> ValueCodec.decodeBoolean(code);
      case DATE -> ValueCodec.decodeDate(code);
      case TIMESTAMP -> ValueCodec.decodeTimestamp(code);
    };
  }

  private static BigDecimal toBigDecimal(Object value) {
    if (value instanceof BigDecimal bd) {
      return bd;
    }
    if (value instanceof Double || value instanceof Float) {
      return BigDecimal.valueOf(((Number) value).doubleValue());
    }
    if (value instanceof Number n) {
      return BigDecimal.valueOf(n.longValue());
    }
    try {
      return new BigDecimal(value.toString());
    } catch (NumberFormatException e) {
      throw new EvaluationException("Expected Decimal, got: " + value, e);
    }
  }
}
