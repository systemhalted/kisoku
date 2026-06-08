package in.systemhalted.kisoku.api.loading;

import in.systemhalted.kisoku.api.RulesetMetadata;
import in.systemhalted.kisoku.api.evaluation.BulkResult;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import java.util.List;
import java.util.concurrent.Executor;

/** Immutable ruleset instance ready for evaluation. */
public interface LoadedRuleset extends AutoCloseable {
  DecisionOutput evaluate(DecisionInput input);

  /**
   * Evaluates each variant (merged over {@code base}) and returns one result per variant in input
   * order. Unlike single {@link #evaluate}, a variant that matches no rule yields an empty result
   * rather than throwing.
   */
  BulkResult evaluateBulk(DecisionInput base, List<DecisionInput> variants);

  /**
   * Throughput-oriented bulk evaluation: scores the variants across the caller-supplied {@code
   * executor} with the given {@code parallelism}. Results are returned in input order, identical to
   * {@link #evaluateBulk(DecisionInput, List)}. The engine never creates its own threads.
   */
  BulkResult evaluateBulk(
      DecisionInput base, List<DecisionInput> variants, Executor executor, int parallelism);

  RulesetMetadata metadata();

  @Override
  void close();
}
