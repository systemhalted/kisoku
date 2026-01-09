package in.systemhalted.kisoku.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class DecisionOutput {
    private final String ruleId;
    private final Map<String, Object> outputs;
    private final MatchDiagnostics diagnostics;

    private DecisionOutput(String ruleId, Map<String, Object> outputs, MatchDiagnostics diagnostics) {
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.outputs = Map.copyOf(outputs);
        this.diagnostics = diagnostics;
    }

    public static DecisionOutput of(String ruleId, Map<String, Object> outputs) {
        return new DecisionOutput(ruleId, outputs, null);
    }

    public static DecisionOutput of(String ruleId, Map<String, Object> outputs, MatchDiagnostics diagnostics) {
        return new DecisionOutput(ruleId, outputs, diagnostics);
    }

    public String ruleId() {
        return ruleId;
    }

    public Map<String, Object> outputs() {
        return outputs;
    }

    public Optional<MatchDiagnostics> diagnostics() {
        return Optional.ofNullable(diagnostics);
    }
}
