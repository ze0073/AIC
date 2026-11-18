package com.shijie.accessibility.api;

import com.shijie.accessibility.service.IssueRepairService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api")
public class IssueRepairController {
    private final IssueRepairService repairs;
    @org.springframework.beans.factory.annotation.Autowired private com.shijie.accessibility.service.RepairWorkflowService workflow;
    public IssueRepairController(IssueRepairService repairs) { this.repairs=repairs; }
    @GetMapping("/issues/{id}/repair-workspace") Object workspace(@PathVariable String id) { return repairs.workspace(id); }
    @PostMapping("/issues/{id}/repair-source") Object upload(@PathVariable String id, @RequestParam MultipartFile file) { return repairs.upload(id,file); }
    @GetMapping("/issues/{id}/repair-files") List<String> files(@PathVariable String id, @RequestParam String versionId) { return repairs.files(id,versionId); }
    @GetMapping("/issues/{id}/repair-file") Object file(@PathVariable String id, @RequestParam String versionId, @RequestParam String path) { return repairs.file(id,versionId,path); }
    @PostMapping("/issues/{id}/repair-candidate") Object candidate(@PathVariable String id, @RequestBody IssueRepairService.CandidateRequest body) { return repairs.candidate(id,body); }
    @PostMapping("/issues/{id}/repairs") Object generate(@PathVariable String id, @RequestHeader("Idempotency-Key") String key, @RequestBody IssueRepairService.GenerateRequest body) { return repairs.generate(id,body,key); }
    @GetMapping("/issue-repairs/{id}") Object get(@PathVariable String id) { return repairs.get(id); }
    @PostMapping("/issue-repairs/{id}/apply") Object apply(@PathVariable String id, @RequestBody Apply body) throws IOException { return workflow.apply(id,body.reviewAccepted()); }
    @GetMapping("/issue-repairs/{id}/download") void download(@PathVariable String id, @RequestParam(defaultValue="false") boolean inline, HttpServletResponse response) throws IOException {
        // Check access and applied state before starting a binary response.
        var detail=repairs.get(id);
        if(detail.newVersionId()==null) throw com.shijie.accessibility.common.ApiException.conflict("REPAIR_NOT_APPLIED","请先应用补丁");
        // The app fetches the bytes before saving, so download managers cannot take over the authenticated request.
        response.setContentType(inline ? "application/x-shijie-source" : "application/zip");
        if(!inline) response.setHeader("Content-Disposition", "attachment; filename=\"repaired-source-"+detail.id()+".zip\"");
        repairs.download(id,response.getOutputStream());
    }
    @PostMapping("/issue-repairs/{id}/retest") Object retest(@PathVariable String id, @RequestHeader("Idempotency-Key") String key, @RequestBody Retest body) { return repairs.retest(id,body.deployed(),key); }
    @PostMapping("/issue-repairs/{id}/verify") Object verify(@PathVariable String id, @RequestHeader("Idempotency-Key") String key) { return repairs.verify(id,key); }
    @PostMapping("/issue-repairs/{id}/link-retest") Object link(@PathVariable String id, @RequestBody LinkRetest body) { return repairs.linkRetest(id,body.runId()); }
    public record Apply(boolean reviewAccepted) {}
    public record Retest(boolean deployed) {}
    public record LinkRetest(String runId) {}
}
