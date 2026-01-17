package in.systemhalted.kisoku.api.evaluation;

/** Signals a failure during ruleset evaluation. */
public class EvaluationException extends RuntimeException {
  public EvaluationException(String message) {
    super(message);
  }

  public EvaluationException(String message, Throwable cause) {
    super(message, cause);
  }
}
