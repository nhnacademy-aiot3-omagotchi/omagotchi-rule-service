package site.omagotchi.ruleservice.core.registry.exception;

public class DuplicateNodeTypeException extends NodeRegistryException {
    public DuplicateNodeTypeException(String message) {
        super(message);
    }
}