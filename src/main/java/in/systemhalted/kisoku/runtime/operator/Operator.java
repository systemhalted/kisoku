package in.systemhalted.kisoku.runtime.operator;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Internal operator definitions and alias normalization.
 */
public enum Operator {
  RULE_ID,
  PRIORITY,
  SET,
  EQ,
  NE,
  GT,
  GTE,
  LT,
  LTE,
  BETWEEN_INCLUSIVE,
  BETWEEN_EXCLUSIVE,
  NOT_BETWEEN_INCLUSIVE,
  NOT_BETWEEN_EXCLUSIVE,
  IN,
  NOT_IN;

  private static final Map<String, Operator> TOKEN_MAP = buildTokenMap();

  /**
   * Normalizes a raw operator token to the canonical {@link Operator}.
   *
   * @throws IllegalArgumentException if the token is empty or unsupported
   */
  public static Operator fromToken(String token) {
    if (token == null) {
      throw new IllegalArgumentException("Operator token must not be null.");
    }
    String normalized = normalize(token);
    Operator operator = TOKEN_MAP.get(normalized);
    if (operator == null) {
      throw new IllegalArgumentException("Unsupported operator token: " + token);
    }
    return operator;
  }

  public boolean isOutput() {
    return this == SET;
  }

  private static String normalize(String token) {
    String normalized = token.trim().toUpperCase(Locale.ROOT);
    return normalized.replaceAll("\\s+", " ");
  }

  private static Map<String, Operator> buildTokenMap() {
    Map<String, Operator> map = new HashMap<>();

    register(map, RULE_ID, "RULE_ID");
    register(map, PRIORITY, "PRIORITY");
    register(map, SET, "SET");

    register(map, EQ, "EQ", "=");
    register(map, NE, "NE", "!=", "NOT EQUAL", "NOT_EQUAL");
    register(map, GT, "GT", ">");
    register(map, GTE, "GTE", ">=");
    register(map, LT, "LT", "<");
    register(map, LTE, "LTE", "<=");

    register(map, BETWEEN_INCLUSIVE, "BETWEEN_INCLUSIVE", "BETWEEN");
    register(map, BETWEEN_EXCLUSIVE, "BETWEEN_EXCLUSIVE");
    register(map, NOT_BETWEEN_INCLUSIVE, "NOT_BETWEEN_INCLUSIVE", "NOT BETWEEN");
    register(map, NOT_BETWEEN_EXCLUSIVE, "NOT_BETWEEN_EXCLUSIVE");

    register(map, IN, "IN");
    register(map, NOT_IN, "NOT IN", "NOT_IN");

    return Collections.unmodifiableMap(map);
  }

  private static void register(Map<String, Operator> map, Operator operator, String... tokens) {
    for (String token : tokens) {
      map.put(normalize(token), operator);
    }
  }
}
