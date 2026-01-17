package in.systemhalted.kisoku.api.loading;

/** Signals a failure while loading a compiled ruleset. */
public class LoadException extends RuntimeException {
  public LoadException(String message) {
    super(message);
  }

  public LoadException(String message, Throwable cause) {
    super(message, cause);
  }
}
