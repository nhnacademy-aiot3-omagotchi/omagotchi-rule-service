package site.omagotchi.ruleservice.core.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import site.omagotchi.ruleservice.core.parser.FlowParser;
import site.omagotchi.ruleservice.core.parser.definition.FlowDefinition;
import site.omagotchi.ruleservice.core.parser.definition.NodeDefinition;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaticFlowLoaderTest {

    @Mock
    private FlowParser flowParser;

    @Mock
    private FlowManager flowManager;

    @Mock
    private ApplicationArguments applicationArguments;

    private StaticFlowLoader staticFlowLoader;

    @BeforeEach
    void setUp() {
        staticFlowLoader = new StaticFlowLoader(flowParser, flowManager);
    }

    @Nested
    @DisplayName("정상 정의만 있을 때 (flows/valid-flow-1.json, flows/valid-flow-2.json)")
    class AllValidDefinitions {

        @Test
        @DisplayName("각 파일 내용이 정확히 매칭되는 FlowDefinition으로 deploy 된다")
        void deploysEachDefinitionExactlyOnceWithCorrectContent() {
            FlowDefinition flowDef1 = new FlowDefinition(
                    "test-flow-1", "정상 플로우 1", null,
                    List.of(new NodeDefinition("nodeA", "SampleSource", null)),
                    null
            );
            FlowDefinition flowDef2 = new FlowDefinition(
                    "test-flow-2", "정상 플로우 2", null,
                    List.of(new NodeDefinition("nodeB", "SampleSink", null)),
                    null
            );

            when(flowParser.parse(anyString())).thenAnswer(invocation -> {
                String json = invocation.getArgument(0);
                if (json.contains("test-flow-1")) {
                    return flowDef1;
                }
                if (json.contains("test-flow-2")) {
                    return flowDef2;
                }
                throw new IllegalStateException("예상치 못한 리소스 내용: " + json);
            });

            staticFlowLoader.run(applicationArguments);

            // 두 정의 모두 정확히 한 번씩, 정확한 내용으로 deploy 호출됐는지 검증
            verify(flowManager, times(1)).deploy(eq(flowDef1));
            verify(flowManager, times(1)).deploy(eq(flowDef2));
            verify(flowManager, times(2)).deploy(any());
        }
    }

    @Nested
    @DisplayName("파싱 실패 격리 (동일 flows/ 디렉토리에 정상 1건 + 실패 유발 1건을 스텁으로 재현)")
    class ParsingFailureIsolation {

        @Test
        @DisplayName("한 리소스의 parse()가 예외를 던져도 나머지 리소스는 정상 배포되고 run()은 예외를 전파하지 않는다")
        void isolatesParsingFailureFromOtherDefinitions() {
            FlowDefinition validDef = new FlowDefinition(
                    "test-flow-1", "정상 플로우 1", null,
                    List.of(new NodeDefinition("nodeA", "SampleSource", null)),
                    null
            );

            when(flowParser.parse(anyString())).thenAnswer(invocation -> {
                String json = invocation.getArgument(0);
                if (json.contains("test-flow-1")) {
                    return validDef;
                }
                // valid-flow-2.json을 "파싱 실패를 유발하는 리소스"로 취급
                throw new RuntimeException("의도적 파싱 실패");
            });

            staticFlowLoader.run(applicationArguments);

            // 실패한 리소스는 deploy가 호출되지 않고, 성공한 리소스만 정확히 한 번 deploy 됨
            verify(flowManager, times(1)).deploy(eq(validDef));
            verify(flowManager, times(1)).deploy(any());
        }

        @Test
        @DisplayName("deploy() 자체가 예외를 던져도 run()은 예외를 전파하지 않고, 다른 리소스는 계속 배포 시도된다")
        void deployFailureIsolatedFromOtherDefinitions() {
            FlowDefinition flowDef1 = new FlowDefinition(
                    "test-flow-1", "정상 플로우 1", null,
                    List.of(new NodeDefinition("nodeA", "SampleSource", null)),
                    null
            );
            FlowDefinition flowDef2 = new FlowDefinition(
                    "test-flow-2", "정상 플로우 2", null,
                    List.of(new NodeDefinition("nodeB", "SampleSink", null)),
                    null
            );

            when(flowParser.parse(anyString())).thenAnswer(invocation -> {
                String json = invocation.getArgument(0);
                if (json.contains("test-flow-1")) {
                    return flowDef1;
                }
                return flowDef2;
            });
            doThrow(new RuntimeException("배포 실패")).when(flowManager).deploy(eq(flowDef1));

            // flowDef1의 deploy가 예외를 던져도 run() 자체는 정상 종료되어야 함 (전파되면 테스트 실패)
            staticFlowLoader.run(applicationArguments);

            // flowDef1은 실패했지만 시도는 됐고, flowDef2는 정상 배포됨
            verify(flowManager, times(1)).deploy(eq(flowDef1));
            verify(flowManager, times(1)).deploy(eq(flowDef2));
        }
    }
}