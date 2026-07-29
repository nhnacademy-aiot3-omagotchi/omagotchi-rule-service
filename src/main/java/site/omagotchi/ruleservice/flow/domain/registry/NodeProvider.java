package site.omagotchi.ruleservice.flow.domain.registry;

import java.util.List;

/**
 * @Component가 붙지 않은 순수 인터페이스
 * 이 인터페이스 자체는 Spring을 몰라도 됨.
 * 나중에 이것을 구현하는 클래스(예: QualityNodeProvider, MessagingNodeProvider)에 @Component를 붙여 Spring Bean 등록
 */
public interface NodeProvider {

    List<NodeDescriptor> provide();
}