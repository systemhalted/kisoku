package in.systemhalted.kisoku.api;

import java.util.List;

/** Diagnostic details about the matched rule and conditions. */
public final class MatchDiagnostics {
  private final List<String> matchedConditions;

  public MatchDiagnostics(List<String> matchedConditions) {
    this.matchedConditions = List.copyOf(matchedConditions);
  }

  public List<String> matchedConditions() {
    return matchedConditions;
  }
}
