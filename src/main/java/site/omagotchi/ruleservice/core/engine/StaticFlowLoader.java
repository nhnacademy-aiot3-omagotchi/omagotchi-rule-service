package site.omagotchi.ruleservice.core.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.core.parser.FlowParser;
import site.omagotchi.ruleservice.core.parser.definition.FlowDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 기동 시 classpath:flows/*.json 전체를 FlowParser로 파싱 -> 전부 flowManager.deploy()
 * 이중화 모드에서도 두 엔진 모두 동일 전체 배포, 실동작은 ACTIVE만
 * 실패 격리: 정의 1건 파싱/배포 실패 시 해당 건만 오류 로그, 나머지는 계속 (전체 기동 실패 금지)
 * 불변 규약: 이 로더 외에 플로우를 등록하는 경로는 없음. FlowManager의 deploy는 이 로더와 테스트만 호출
 * <p>
 * ApplicationRunner는 Spring Boot가 제공하는 인터페이스로, 애플리케이션이 완전히 기동된 직후 자동으로 실행되는 훅
 * -> @Component로 등록되어야 Spring이 기동 시점에 자동으로 호출해줌
 * <p>
 * classpath:flows/*.json 처럼 와일드카드 패턴으로 여러 파일 찾기 -> Spring의 PathMatchingResourcePatternResolver 사용
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StaticFlowLoader implements ApplicationRunner {

    private static final String FLOW_DIR = "classpath:flows/";
    private static final String FLOW_DEFINITION_LOCATION_PATTERN = FLOW_DIR + "*.json";

    private final FlowParser flowParser;
    private final FlowManager flowManager;

    /**
     * run()이 개별 배포 예외를 deployOne() 안에서 삼킴
     * 정의 1건 실패 시 해당 건만 오류 로그, 나머지는 계속 (기동 자체를 절대 막으면 안 됨)
     */
    @Override
    public void run(ApplicationArguments args) {
        Resource[] resources = this.resolveFlowDefinitionResources();

        if (resources.length == 0) {
            return;
        }

        int success = 0;
        int fail = 0;

        for (Resource resource : resources) {
            if (this.deployOne(resource)) {
                success++;
            } else {
                fail++;
            }
        }

        log.info("[StaticFlowLoader] 정적 플로우 배포 완료 - 성공 {}건, 실패 {}건 (전체 {}건)", success, fail, resources.length);
    }

    private boolean deployOne(Resource resource) {
        try {
            String json = this.readResource(resource);
            FlowDefinition flowDef = this.flowParser.parse(json);
            this.flowManager.deploy(flowDef);

            log.debug("[StaticFlowLoader] '{}' 플로우 배포 성공 (resource = {})", flowDef.id(), resource.getFilename());
            return true;
        } catch (Exception e) {
            log.error("[StaticFlowLoader] 플로우 배포 실패: {} - 이 정의는 건너뛰고 계속 진행합니다.", resource.getFilename(), e);
            return false;
        }
    }

    /**
     * 리소스 조회 자체가 실패해도(IOException) 빈 배열을 리턴하고 계속 진행함
     * classpath 패턴 매칭 자체가 실패하는 상황이여도 애플리케이션 기동을 막지 않음
     */
    private Resource[] resolveFlowDefinitionResources() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(); // 주입받을수도 있지만, 스프링 컨텍스트 전체에 의존하게 되어서 new로 직접 생성

        Resource dir = resolver.getResource(FLOW_DIR);

        if (!dir.exists()) {
            log.info("[StaticFlowLoader] flows/ 디렉토리가 없습니다. 정적 플로우 없이 기동합니다.");
            return new Resource[0];
        }

        try {
            return resolver.getResources(FLOW_DEFINITION_LOCATION_PATTERN);
        } catch (IOException e) {
            log.warn("[StaticFlowLoader] {} 패턴을 조회하는 중 오류가 발생했습니다. 플로우 없이 계속 진행합니다.", FLOW_DEFINITION_LOCATION_PATTERN, e);
            return new Resource[0];
        }
    }

    private String readResource(Resource resource) throws IOException {
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}