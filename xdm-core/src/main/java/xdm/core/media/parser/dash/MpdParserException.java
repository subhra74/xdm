package xdm.core.media.parser.dash;

public class MpdParserException extends Exception {
  public MpdParserException(String message, Throwable t) {
    super(message, t);
  }

  public MpdParserException(String message) {
    super(message);
  }
}
