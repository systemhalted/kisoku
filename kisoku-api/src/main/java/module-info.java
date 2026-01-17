/**
 * Kisoku API module - public contracts for the decision table rule engine.
 *
 * <p>This module exports all public interfaces, models, and exceptions that library consumers
 * depend on. Implementation details are in the kisoku.runtime module.
 */
module kisoku.api {
  exports in.systemhalted.kisoku.api;
  exports in.systemhalted.kisoku.api.validation;
  exports in.systemhalted.kisoku.api.compilation;
  exports in.systemhalted.kisoku.api.loading;
  exports in.systemhalted.kisoku.api.evaluation;

  uses in.systemhalted.kisoku.api.validation.RulesetValidator;
  uses in.systemhalted.kisoku.api.compilation.RulesetCompiler;
  uses in.systemhalted.kisoku.api.loading.RulesetLoader;
}
