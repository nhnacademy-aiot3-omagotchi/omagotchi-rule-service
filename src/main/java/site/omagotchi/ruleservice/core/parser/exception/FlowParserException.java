package site.omagotchi.ruleservice.core.parser.exception;

public class FlowParserException extends RuntimeException {

    public FlowParserException(String message) {
        super(message);
    }

    public FlowParserException(String message, Throwable cause) {
        super(message, cause);
    }
}