package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.dto.PortalPlanDto;
import com.techcomfort.landvaultbackend.payments.internal.service.PortalPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Phase 4: a developer's running instalment plans and which are late. See {@link PortalPlanService}. */
@RestController
@RequestMapping("/api/portal/finance/plans")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_FINANCE)
public class PortalPlanController {

    private final PortalPlanService plans;

    @Operation(summary = "Running instalment plans and arrears",
            description = """
                    Requires `portal.payments.verify`. Your company's plans whose deposit is verified and that \\
                    aren't paid off yet; a branch-scoped officer sees only their branch's. Each shows where it \\
                    stands **today** — `on_track`, `due_today`, `overdue` (1–7 days late) or `in_arrears` (more \\
                    than 7) — and, while late, what's owed and the penalty reached. Worked out on read, never \\
                    stored; the buyer sees the same. Most urgent first. Filter with `status`, or `late` for \\
                    overdue and in arrears together. Read-only.""")
    @GetMapping
    @PreAuthorize("hasAuthority('portal.payments.verify')")
    public ResponseEntity<List<PortalPlanDto>> plans(
            @Parameter(description = "`on_track`, `due_today`, `overdue`, `in_arrears` or `late`.")
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(plans.plans(status));
    }
}
