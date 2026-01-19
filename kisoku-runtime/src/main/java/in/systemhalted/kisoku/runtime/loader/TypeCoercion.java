package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.evaluation.EvaluationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Utility for type conversion between DecisionInput values and stored values. */
final class TypeCoercion {
  private TypeCoercion() {}

  /**
   * Convert input value to comparable integer for matching.
   *
   * @param value the input value from DecisionInput
   * @param type the column type
   * @param dictionary the string dictionary for lookup
   * @return the comparable integer representation
   */
  static int toComparableInt(Object value, ColumnType type, StringDictionaryReader dictionary) {
    if (value == null) {
      return StringDictionaryReader.NULL_ID;
    }

    return switch (type) {
      case STRING -> {
        String s = value.toString();
        yield dictionary.getId(s);
      }
      case INTEGER -> {
        if (value instanceof Number n) {
          yield n.intValue();
        }
        throw new EvaluationException("Expected Integer, got: " + value.getClass().getName());
      }
      case DECIMAL -> {
        // For decimal comparison, use dictionary lookup
        String decStr = value instanceof BigDecimal bd ? bd.toPlainString() : value.toString();
        yield dictionary.getId(decStr);
      }
      case BOOLEAN -> {
        if (value instanceof Boolean b) {
          yield b ? 1 : 0;
        }
        throw new EvaluationException("Expected Boolean, got: " + value.getClass().getName());
      }
      case DATE -> {
        if (value instanceof LocalDate ld) {
          yield (int) ld.toEpochDay();
        } else if (value instanceof Integer i) {
          yield i;
        }
        throw new EvaluationException(
            "Expected LocalDate or Integer, got: " + value.getClass().getName());
      }
      case TIMESTAMP -> {
        // Timestamps stored as dictionary strings
        String ts = value instanceof Instant inst ? inst.toString() : value.toString();
        yield dictionary.getId(ts);
      }
    };
  }

  /**
   * Decode stored value back to Java object for output.
   *
   * @param storedValue the stored integer value
   * @param type the column type
   * @param dictionary the string dictionary
   * @return the decoded Java object
   */
  static Object decodeValue(int storedValue, ColumnType type, StringDictionaryReader dictionary) {
    if (storedValue == StringDictionaryReader.NULL_ID
        && type != ColumnType.INTEGER
        && type != ColumnType.BOOLEAN) {
      return null;
    }

    return switch (type) {
      case STRING -> dictionary.get(storedValue);
      case INTEGER -> storedValue;
      case DECIMAL -> dictionary.get(storedValue); // Return as string for precision
      case BOOLEAN -> storedValue != 0;
      case DATE -> LocalDate.ofEpochDay(storedValue);
      case TIMESTAMP -> dictionary.get(storedValue); // Return as string
    };
  }
}
