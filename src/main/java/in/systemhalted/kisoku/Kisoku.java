package in.systemhalted.kisoku;

import in.systemhalted.kisoku.api.RulesetCompiler;
import in.systemhalted.kisoku.api.RulesetLoader;
import in.systemhalted.kisoku.api.RulesetValidator;
import in.systemhalted.kisoku.runtime.UnsupportedRulesetCompiler;
import in.systemhalted.kisoku.runtime.UnsupportedRulesetLoader;
import in.systemhalted.kisoku.runtime.UnsupportedRulesetValidator;

/** Library entry point providing access to validator, compiler, and loader instances. */
public final class Kisoku {
  private Kisoku() {}

  public static RulesetValidator validator() {
    return new UnsupportedRulesetValidator();
  }

  public static RulesetCompiler compiler() {
    return new UnsupportedRulesetCompiler();
  }

  public static RulesetLoader loader() {
    return new UnsupportedRulesetLoader();
  }
}
