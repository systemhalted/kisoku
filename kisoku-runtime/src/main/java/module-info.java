/**
 * Kisoku runtime module - implementation of the decision table rule engine.
 *
 * <p>This module provides implementations for validator, compiler, and loader. It does not export
 * any packages - all access is through the kisoku.api module's ServiceLoader discovery.
 */
module kisoku.runtime {
  requires kisoku.api;
  requires java.management; // For memory monitoring (MemoryMXBean, BufferPoolMXBean)

  provides in.systemhalted.kisoku.api.validation.RulesetValidator with
      in.systemhalted.kisoku.runtime.csv.CsvRulesetValidator;
  provides in.systemhalted.kisoku.api.compilation.RulesetCompiler with
      in.systemhalted.kisoku.runtime.compiler.CsvRulesetCompiler;
  provides in.systemhalted.kisoku.api.loading.RulesetLoader with
      in.systemhalted.kisoku.runtime.loader.CsvRulesetLoader;
}
