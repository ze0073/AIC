package com.shijie.accessibility.service;

import com.shijie.accessibility.hosting.HostingService;
import org.springframework.stereotype.Service;
import java.io.IOException;

/** Both the original editor and the guide use the same apply/deploy/retest orchestration. */
@Service
public class RepairWorkflowService {
    private final IssueRepairService repairs;
    private final HostingService hosting;
    public RepairWorkflowService(IssueRepairService repairs, HostingService hosting) {
        this.repairs=repairs; this.hosting=hosting;
    }
    public IssueRepairService.RepairDetail apply(String id, boolean accepted) throws IOException {
        repairs.apply(id, accepted);
        hosting.autoRepair(id);
        return repairs.get(id);
    }
}
