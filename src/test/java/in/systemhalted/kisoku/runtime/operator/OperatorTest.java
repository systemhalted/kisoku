package in.systemhalted.kisoku.runtime.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OperatorTest {
  @Test
  void normalizesAliases() {
    assertEquals(Operator.RULE_ID, Operator.fromToken("RULE_ID"));
    assertEquals(Operator.PRIORITY, Operator.fromToken("PRIORITY"));
    assertEquals(Operator.SET, Operator.fromToken("SET"));
    assertEquals(Operator.EQ, Operator.fromToken("EQ"));
    assertEquals(Operator.NE, Operator.fromToken("NE"));
    assertEquals(Operator.GT, Operator.fromToken("GT"));
    assertEquals(Operator.GTE, Operator.fromToken("GTE"));
    assertEquals(Operator.LT, Operator.fromToken("LT"));
    assertEquals(Operator.LTE, Operator.fromToken("LTE"));
    assertEquals(Operator.BETWEEN_INCLUSIVE, Operator.fromToken("BETWEEN_INCLUSIVE"));
    assertEquals(Operator.BETWEEN_EXCLUSIVE, Operator.fromToken("BETWEEN_EXCLUSIVE"));
    assertEquals(Operator.NOT_BETWEEN_INCLUSIVE, Operator.fromToken("NOT_BETWEEN_INCLUSIVE"));
    assertEquals(Operator.NOT_BETWEEN_EXCLUSIVE, Operator.fromToken("NOT_BETWEEN_EXCLUSIVE"));
    assertEquals(Operator.IN, Operator.fromToken("IN"));
    assertEquals(Operator.NOT_IN, Operator.fromToken("NOT IN"));
    assertEquals(Operator.EQ, Operator.fromToken("="));
    assertEquals(Operator.NE, Operator.fromToken("!="));
    assertEquals(Operator.NE, Operator.fromToken("NOT EQUAL"));
    assertEquals(Operator.NE, Operator.fromToken("NOT_EQUAL"));
    assertEquals(Operator.GT, Operator.fromToken(">"));
    assertEquals(Operator.GTE, Operator.fromToken(">="));
    assertEquals(Operator.LT, Operator.fromToken("<"));
    assertEquals(Operator.LTE, Operator.fromToken("<="));
    assertEquals(Operator.BETWEEN_INCLUSIVE, Operator.fromToken("BETWEEN"));
    assertEquals(Operator.BETWEEN_EXCLUSIVE, Operator.fromToken("BETWEEN_EXCLUSIVE"));
    assertEquals(Operator.NOT_BETWEEN_INCLUSIVE, Operator.fromToken("NOT BETWEEN"));
    assertEquals(Operator.NOT_BETWEEN_EXCLUSIVE, Operator.fromToken("NOT_BETWEEN_EXCLUSIVE"));
    assertEquals(Operator.NOT_IN, Operator.fromToken("NOT IN"));
  }

  @Test
  void trimsAndUppercasesTokens() {
    assertEquals(Operator.IN, Operator.fromToken(" in "));
    assertEquals(Operator.NOT_IN, Operator.fromToken(" not  in "));
    assertEquals(Operator.EQ, Operator.fromToken(" = "));
    assertEquals(Operator.NE, Operator.fromToken(" != "));
    assertEquals(Operator.GT, Operator.fromToken(" > "));
    assertEquals(Operator.GTE, Operator.fromToken(" >= "));
    assertEquals(Operator.LT, Operator.fromToken(" < "));
    assertEquals(Operator.LTE, Operator.fromToken(" <= "));
    assertEquals(Operator.BETWEEN_INCLUSIVE, Operator.fromToken("  between  "));
    assertEquals(Operator.NOT_BETWEEN_INCLUSIVE, Operator.fromToken(" not   between "));
    assertEquals(Operator.NOT_IN, Operator.fromToken(" not   in "));
  }

  @Test
  void rejectsUnknownTokens() {
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> Operator.fromToken("UNKNOWN"));
    assertEquals("Unsupported operator token: UNKNOWN", exception.getMessage());
  }

  @Test
  void rejectsNullTokens() {
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> Operator.fromToken(null));
    assertEquals("Operator token must not be null.", exception.getMessage());
  }
}
