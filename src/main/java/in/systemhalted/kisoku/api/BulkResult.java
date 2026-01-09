package in.systemhalted.kisoku.api;

import java.util.List;

/** Holds ordered results for bulk evaluation. */
public final class BulkResult {
  private final List<DecisionOutput> results;

  public BulkResult(List<DecisionOutput> results) {
    this.results = List.copyOf(results);
  }

  public List<DecisionOutput> results() {
    return results;
  }
}
