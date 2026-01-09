package in.systemhalted.kisoku.api.loader;

import in.systemhalted.kisoku.api.evaluation.BulkResult;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import in.systemhalted.kisoku.api.model.RulesetMetadata;
import java.util.List;

/** Immutable ruleset instance ready for evaluation. */
public interface LoadedRuleset extends AutoCloseable {
  DecisionOutput evaluate(DecisionInput input);

  BulkResult evaluateBulk(DecisionInput base, List<DecisionInput> variants);

  RulesetMetadata metadata();

  @Override
  void close();
}
