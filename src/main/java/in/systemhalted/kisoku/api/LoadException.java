package in.systemhalted.kisoku.api;

public class LoadException extends RuntimeException {
    public LoadException(String message) {
        super(message);
    }

    public LoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
