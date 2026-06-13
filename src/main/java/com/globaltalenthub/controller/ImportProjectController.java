package com.globaltalenthub.controller;

import com.globaltalenthub.security.AuthenticatedUser;
import com.globaltalenthub.service.ImportProjectService;
import com.globaltalenthub.service.ImportProjectService.ImportResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Bulk project import from mapped tabular records. Port of importProject.ts. */
@RestController
@RequiredArgsConstructor
public class ImportProjectController {

    private final ImportProjectService importProjectService;

    @PostMapping("/api/import-project")
    public ImportResult importProject(@RequestBody Map<String, Object> body,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return importProjectService.importProject(body, user.orgId(), user.userId());
    }
}
