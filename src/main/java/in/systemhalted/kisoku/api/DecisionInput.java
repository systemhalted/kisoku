package in.systemhalted.kisoku.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class DecisionInput {
  private final Map<String, Object> values;

  private DecisionInput(Map<String, Object> values) {
    this.values = Map.copyOf(values);
  }

  public static DecisionInput of(Map<String, Object> values) {
    Objects.requireNonNull(values, "values");
    return new DecisionInput(values);
  }

  public static DecisionInput empty() {
    return new DecisionInput(Map.of());
  }

  public Optional<Object> get(String key) {
    return Optional.ofNullable(values.get(key));
  }

  public Map<String, Object> values() {
    return values;
  }
}
