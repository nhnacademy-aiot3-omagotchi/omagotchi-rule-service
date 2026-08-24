package site.omagotchi.ruleservice.distributed.application.port;

import site.omagotchi.ruleservice.distributed.domain.EngineInfo;

import java.util.List;

/**
 * 지금 알려진 엔진들이 누구고 어떤 상태인지 조회하는 창구
 */
public interface EngineDirectoryPort {

    List<EngineInfo> listEngines();
}