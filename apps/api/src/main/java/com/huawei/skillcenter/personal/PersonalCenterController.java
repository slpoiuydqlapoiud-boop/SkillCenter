package com.huawei.skillcenter.personal;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.governance.InstallationRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/me")
public class PersonalCenterController {
    private final PersonalCenterService service;
    private final ActorResolver actorResolver;

    public PersonalCenterController(PersonalCenterService service, ActorResolver actorResolver) {
        this.service = service;
        this.actorResolver = actorResolver;
    }

    @GetMapping("/installations")
    ResponseEntity<ApiResponse<List<InstallationRecord>>> installations(@RequestParam(required = false) String status,
                                                                          @RequestParam(required = false) String skillId,
                                                                          @RequestParam(required = false) String clientType,
                                                                          HttpServletRequest request) {
        return ok(service.installations(actorResolver.resolve(request), status, skillId, clientType), request);
    }

    @GetMapping("/skills")
    ResponseEntity<ApiResponse<List<PersonalSkillView>>> skills(HttpServletRequest request) {
        return ok(service.mySkills(actorResolver.resolve(request)), request);
    }

    @GetMapping("/favorites")
    ResponseEntity<ApiResponse<List<PersonalSkillView>>> favorites(HttpServletRequest request) {
        return ok(service.favorites(actorResolver.resolve(request)), request);
    }

    @PutMapping("/favorites/{skillId}")
    ResponseEntity<ApiResponse<PersonalSkillView>> addFavorite(@PathVariable String skillId, HttpServletRequest request) {
        return ok(service.addFavorite(actorResolver.resolve(request), skillId, requestId(request)), request);
    }

    @DeleteMapping("/favorites/{skillId}")
    ResponseEntity<ApiResponse<Void>> removeFavorite(@PathVariable String skillId, HttpServletRequest request) {
        service.removeFavorite(actorResolver.resolve(request), skillId, requestId(request));
        return ok(null, request);
    }

    @GetMapping("/invocations")
    ResponseEntity<ApiResponse<InvocationHistoryPage>> invocations(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String skillId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        return ok(service.invocations(actorResolver.resolve(request), from, to, skillId, status, page, pageSize), request);
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data, HttpServletRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(data, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
