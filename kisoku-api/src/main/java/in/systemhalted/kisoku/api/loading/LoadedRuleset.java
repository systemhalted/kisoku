package in.systemhalted.kisoku.api.loading;

import in.systemhalted.kisoku.api.RulesetMetadata;
import in.systemhalted.kisoku.api.evaluation.BulkResult;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import java.util.List;

/** Immutable ruleset instance ready for evaluation. */
public interface LoadedRuleset extends AutoCloseable {
  DecisionOutput evaluate(DecisionInput input);

  BulkResult evaluateBulk(DecisionInput base, List<DecisionInput> variants);

  RulesetMetadata metadata();

  @Override
  void close();
}
