package com.shijie.accessibility.ai;

import java.util.List;
import java.util.Map;

/** The model proposes actions; the agent service validates every proposal before execution. */
public interface AgentModelClient {
    boolean available();
    String unavailableReason();
    String model();
    Plan propose(Map<String,Object> context);
    Decision decide(Map<String,Object> context);

    record Plan(List<String> modules, boolean includeFlow, String reason) {}
    record Decision(String action, String module, String query, String reason, List<Integer> evidenceStepNos) {
        public Decision(String action, String module, String query, String reason) {
            this(action,module,query,reason,List.of());
        }
    }
}
