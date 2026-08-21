package in.systemhalted.kisoku.api.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds ordered results for bulk evaluation, one entry per submitted variant.
 *
 * <p>Results are positional: entry {@code i} corresponds to variant {@code i}. A variant that
 * matched no rule yields an <em>empty</em> entry rather than failing the batch, so one unmatched
 * variant never discards the results of the others. Use {@link #result(int)} or {@link
 * #matched(int)} to read entries safely; {@link #results()} exposes the raw positional list, in
 * which unmatched entries are {@code null}.
 */
public final class BulkResult {
  private final List<DecisionOutput> results;

  /**
   * Creates a bulk result.
   *
   * @param results positional results, one per variant; a {@code null} entry means "no rule matched
   *     this variant"
   */
  public BulkResult(List<DecisionOutput> results) {
    Objects.requireNonNull(results, "results");
    // Not List.copyOf: unmatched variants are represented as null entries to keep positions
    // aligned.
    this.results = Collections.unmodifiableList(new ArrayList<>(results));
  }

  /**
   * The positional results, one per variant. Entries for unmatched variants are {@code null}.
   *
   * @return an unmodifiable list, possibly containing nulls
   */
  public List<DecisionOutput> results() {
    return results;
  }

  /**
   * The result for a single variant.
   *
   * @param index the variant position
   * @return the decision output, or empty if no rule matched that variant
   */
  public Optional<DecisionOutput> result(int index) {
    return Optional.ofNullable(results.get(index));
  }

  /**
   * Whether the variant at the given position matched a rule.
   *
   * @param index the variant position
   * @return true if a rule matched
   */
  public boolean matched(int index) {
    return results.get(index) != null;
  }

  /**
   * Number of variants evaluated.
   *
   * @return the result count, equal to the number of submitted variants
   */
  public int size() {
    return results.size();
  }

  /**
   * Number of variants that matched a rule.
   *
   * @return the matched count
   */
  public int matchedCount() {
    int count = 0;
    for (DecisionOutput output : results) {
      if (output != null) {
        count++;
      }
    }
    return count;
  }

  /**
   * Positions of variants that matched no rule.
   *
   * @return the unmatched variant indices, in ascending order
   */
  public List<Integer> unmatchedIndices() {
    List<Integer> unmatched = new ArrayList<>();
    for (int i = 0; i < results.size(); i++) {
      if (results.get(i) == null) {
        unmatched.add(i);
      }
    }
    return List.copyOf(unmatched);
  }
}
