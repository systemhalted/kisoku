package in.systemhalted.kisoku;

import in.systemhalted.kisoku.api.compiler.RulesetCompiler;
import in.systemhalted.kisoku.api.loader.RulesetLoader;
import in.systemhalted.kisoku.api.validator.RulesetValidator;
import in.systemhalted.kisoku.runtime.exception.UnsupportedRulesetCompiler;
import in.systemhalted.kisoku.runtime.exception.UnsupportedRulesetLoader;
import in.systemhalted.kisoku.runtime.validator.CsvRulesetValidator;

/** Library entry point providing access to validator, compiler, and loader instances. */
public final class Kisoku {
  private Kisoku() {}

  public static RulesetValidator validator() {
    return new CsvRulesetValidator();
  }

  public static RulesetCompiler compiler() {
    return new UnsupportedRulesetCompiler();
  }

  public static RulesetLoader loader() {
    return new UnsupportedRulesetLoader();
  }
}
