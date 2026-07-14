package site.omagotchi.ruleservice.global.exception;

// 각 도메인에서 상속해 사용
public interface ErrorCode {

    ErrorType type();

    String code();

    String message();
}
