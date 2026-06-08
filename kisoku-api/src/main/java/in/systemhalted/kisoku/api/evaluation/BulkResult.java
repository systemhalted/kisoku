package in.systemhalted.kisoku.api.evaluation;

import java.util.List;
import java.util.Optional;

/**
 * Holds ordered results for bulk evaluation, one entry per variant in input order.
 *
 * <p>Bulk evaluation does not abort on a non-matching variant: a variant that matched no rule is
 * represented as an {@link Optional#empty()} entry, in contrast to single {@code evaluate}, which
 * throws when no rule matches.
 */
public final class BulkResult {
  private final List<Optional<DecisionOutput>> results;

  public BulkResult(List<Optional<DecisionOutput>> results) {
    this.results = List.copyOf(results);
  }

  /** Per-variant results in input order; an empty {@code Optional} means no rule matched. */
  public List<Optional<DecisionOutput>> results() {
    return results;
  }

  /** Number of variant results (equals the number of variants evaluated). */
  public int size() {
    return results.size();
  }

  /** The result for the variant at {@code index}; empty if no rule matched. */
  public Optional<DecisionOutput> get(int index) {
    return results.get(index);
  }
}
