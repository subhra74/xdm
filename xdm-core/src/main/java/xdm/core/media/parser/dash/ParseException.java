package xdm.core.media.parser.dash;

public class ParseException extends RuntimeException {
  public ParseException(String message, Throwable t) {
    super(message, t);
  }

  public ParseException(String message) {
    super(message);
  }
}
