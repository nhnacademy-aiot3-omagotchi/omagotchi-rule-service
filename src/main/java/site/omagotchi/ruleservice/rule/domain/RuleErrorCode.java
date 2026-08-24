package site.omagotchi.ruleservice.rule.domain;

import lombok.RequiredArgsConstructor;
import site.omagotchi.ruleservice.global.exception.ErrorCode;
import site.omagotchi.ruleservice.global.exception.ErrorType;

@RequiredArgsConstructor
public enum RuleErrorCode implements ErrorCode {
    ;

    private final ErrorType type;
    private final String code;
    private final String message;

    @Override
    public ErrorType type() {
        return type;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
