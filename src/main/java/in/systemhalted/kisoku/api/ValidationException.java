package in.systemhalted.kisoku.api;

/**
 * Signals a validation failure when parsing or checking a decision table.
 */
public class ValidationException extends RuntimeException {
  public ValidationException(String message) {
    super(message);
  }

  public ValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
