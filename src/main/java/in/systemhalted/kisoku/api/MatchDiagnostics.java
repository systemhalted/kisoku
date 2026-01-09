package in.systemhalted.kisoku.api;

import java.util.List;

public final class MatchDiagnostics {
  private final List<String> matchedConditions;

  public MatchDiagnostics(List<String> matchedConditions) {
    this.matchedConditions = List.copyOf(matchedConditions);
  }

  public List<String> matchedConditions() {
    return matchedConditions;
  }
}
