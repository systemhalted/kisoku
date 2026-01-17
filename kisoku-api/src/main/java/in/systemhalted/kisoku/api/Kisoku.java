package in.systemhalted.kisoku.api;

import in.systemhalted.kisoku.api.compilation.RulesetCompiler;
import in.systemhalted.kisoku.api.loading.RulesetLoader;
import in.systemhalted.kisoku.api.validation.RulesetValidator;
import java.util.ServiceLoader;

/** Library entry point providing access to validator, compiler, and loader instances. */
public final class Kisoku {
  private Kisoku() {}

  /**
   * Returns a RulesetValidator implementation discovered via ServiceLoader.
   *
   * @throws IllegalStateException if no implementation is available
   */
  public static RulesetValidator validator() {
    return ServiceLoader.load(RulesetValidator.class)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No RulesetValidator implementation found. "
                        + "Add kisoku-runtime to your classpath."));
  }

  /**
   * Returns a RulesetCompiler implementation discovered via ServiceLoader.
   *
   * @throws IllegalStateException if no implementation is available
   */
  public static RulesetCompiler compiler() {
    return ServiceLoader.load(RulesetCompiler.class)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No RulesetCompiler implementation found. "
                        + "Add kisoku-runtime to your classpath."));
  }

  /**
   * Returns a RulesetLoader implementation discovered via ServiceLoader.
   *
   * @throws IllegalStateException if no implementation is available
   */
  public static RulesetLoader loader() {
    return ServiceLoader.load(RulesetLoader.class)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No RulesetLoader implementation found. "
                        + "Add kisoku-runtime to your classpath."));
  }
}
