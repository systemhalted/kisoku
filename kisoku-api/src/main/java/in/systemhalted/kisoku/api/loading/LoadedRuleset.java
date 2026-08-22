package in.systemhalted.kisoku.api.loading;

import in.systemhalted.kisoku.api.RulesetMetadata;
import in.systemhalted.kisoku.api.evaluation.BulkResult;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import in.systemhalted.kisoku.api.evaluation.EvaluationException;
import in.systemhalted.kisoku.api.evaluation.MatchDiagnostics;
import java.util.List;
import java.util.concurrent.Executor;

/** Immutable ruleset instance ready for evaluation. */
public interface LoadedRuleset extends AutoCloseable {
  /**
   * Evaluates one input against the ruleset.
   *
   * @param input the input payload
   * @return the winning rule's output
   * @throws EvaluationException if no rule matches
   */
  DecisionOutput evaluate(DecisionInput input);

  /**
   * Evaluates one input and attaches {@link MatchDiagnostics}: the winning rule's non-blank
   * conditions, rendered with the column name, operator, and stored operand.
   *
   * <p>Slower than {@link #evaluate(DecisionInput)} (conditions are decoded and rendered); use it
   * for authoring, debugging, and audit trails rather than the hot path.
   *
   * @param input the input payload
   * @return the winning rule's output with diagnostics populated
   * @throws EvaluationException if no rule matches
   */
  DecisionOutput explain(DecisionInput input);

  /**
   * Evaluates a shared base input against a collection of variants, sequentially.
   *
   * <p>Variant fields overlay the base per evaluation. Each variant is independent: one that
   * matches no rule contributes an empty entry in the result rather than failing the batch.
   *
   * @param base the shared base input (fields a variant does not override)
   * @param variants the per-evaluation overlays
   * @return one result per variant, positionally aligned
   */
  BulkResult evaluateBulk(DecisionInput base, List<DecisionInput> variants);

  /**
   * Evaluates a bulk batch across a caller-supplied executor.
   *
   * <p>Same semantics as {@link #evaluateBulk(DecisionInput, List)} - identical results, one per
   * variant - with the batch partitioned into up to {@code parallelism} disjoint chunks executed
   * concurrently. The ruleset is immutable and thread-safe, so no synchronization is needed beyond
   * what the executor provides. The engine never creates its own threads.
   *
   * @param base the shared base input
   * @param variants the per-evaluation overlays
   * @param executor the executor to run chunks on
   * @param parallelism maximum concurrent chunks; values below 2 evaluate sequentially
   * @return one result per variant, positionally aligned
   */
  BulkResult evaluateBulk(
      DecisionInput base, List<DecisionInput> variants, Executor executor, int parallelism);

  RulesetMetadata metadata();

  @Override
  void close();
}
