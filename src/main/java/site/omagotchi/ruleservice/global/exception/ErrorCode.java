package site.omagotchi.ruleservice.global.exception;

import org.springframework.http.HttpStatus;

// 각 도메인에서 상속해 사용
// 현재는 http status가 들어가서, domain 패키지보단 exception 패키지에 넣어서 사용
public interface ErrorCode {

    HttpStatus status();

    String code();

    String message();
}
