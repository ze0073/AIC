package com.shijie.accessibility.api;

import com.shijie.accessibility.service.GuideService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.List;

@RestController
@RequestMapping("/api/guide")
public class GuideController {
    private final GuideService guide;
    public GuideController(GuideService guide){this.guide=guide;}
    @GetMapping("/preferences") GuideService.Preferences preferences(){return guide.preferences();}
    @PutMapping("/preferences") GuideService.Preferences preferences(@RequestBody GuideService.PreferenceUpdate body){return guide.preferences(body);}
    @GetMapping("/sessions") List<GuideService.Session> list(){return guide.list();}
    @PostMapping("/sessions") @ResponseStatus(HttpStatus.CREATED) GuideService.Session create(@RequestBody GuideService.Create body,@RequestHeader("Idempotency-Key") String key){return guide.create(body,key);}
    @GetMapping("/sessions/{id}") GuideService.Session get(@PathVariable String id){return guide.get(id);}
    @PatchMapping("/sessions/{id}") GuideService.Session patch(@PathVariable String id,@RequestBody GuideService.Patch body){return guide.patch(id,body);}
    @PostMapping("/sessions/{id}/messages") GuideService.Session message(@PathVariable String id,@RequestBody GuideService.Send body,@RequestHeader("Idempotency-Key") String key){return guide.send(id,body,key);}
    @PostMapping("/actions/{id}/confirm") GuideService.Session confirm(@PathVariable String id,@RequestBody Confirm body,@RequestHeader("Idempotency-Key") String key){return guide.confirm(id,body.actionHash(),key,body.reviewAccepted());}
    @PostMapping("/actions/{id}/cancel") GuideService.Session cancel(@PathVariable String id,@RequestHeader("Idempotency-Key") String key){return guide.cancel(id,key);}
    public record Confirm(String actionHash,boolean reviewAccepted){}
}
