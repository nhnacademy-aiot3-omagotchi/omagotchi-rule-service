package site.omagotchi.ruleservice.flow.domain.registry;

import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;

import java.util.Map;

@FunctionalInterface
public interface NodeFactory {

    AbstractNode create(Map<String, Object> config);
}