package in.systemhalted.kisoku.api;

import java.util.List;

public interface LoadedRuleset extends AutoCloseable {
    DecisionOutput evaluate(DecisionInput input);

    BulkResult evaluateBulk(DecisionInput base, List<DecisionInput> variants);

    RulesetMetadata metadata();

    @Override
    void close();
}
