package site.omagotchi.ruleservice.core.registry;

import site.omagotchi.ruleservice.core.node.AbstractNode;

import java.util.Map;

@FunctionalInterface
public interface NodeFactory {

    AbstractNode create(Map<String, Object> config);
}