package in.systemhalted.kisoku.api.compiler;

/** Signals a failure during ruleset compilation. */
public class CompilationException extends RuntimeException {
  public CompilationException(String message) {
    super(message);
  }

  public CompilationException(String message, Throwable cause) {
    super(message, cause);
  }
}
