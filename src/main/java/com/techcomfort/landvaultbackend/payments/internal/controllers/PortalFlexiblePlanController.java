package com.techcomfort.landvaultbackend.payments.internal.controllers;

import com.techcomfort.landvaultbackend.common.OpenApiConfig;
import com.techcomfort.landvaultbackend.payments.dto.PortalFlexiblePlanDto;
import com.techcomfort.landvaultbackend.payments.internal.service.PortalPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Flexible contribution, phase 4: a developer's running flexible plans. See {@link PortalPlanService#flexiblePlans}. */
@RestController
@RequestMapping("/api/portal/finance/flexible-plans")
@RequiredArgsConstructor
@Tag(name = OpenApiConfig.TAG_PORTAL_FINANCE)
public class PortalFlexiblePlanController {

    private final PortalPlanService plans;

    @Operation(summary = "Running flexible plans, and which have gone quiet",
            description = """
                    Requires `portal.payments.verify`. Your company's flexible plans whose first payment is verified \
                    and that aren't finished; a branch-scoped officer sees only their branch's. Each shows what's \
                    paid and left, the last payment Paystack confirmed, the day it would count as quiet, its date \
                    to finish by, and whether notice may be given today (`standing.noticeReason`: `inactivity` or \
                    `finish_by`). Nothing on a flexible plan is ever overdue. The ones notice may be given on come \
                    first. Worked out on read, never stored; the buyer sees the same. Read-only: giving notice is \
                    the Executive Director's, through the revocation routes.""")
    @GetMapping
    @PreAuthorize("hasAuthority('portal.payments.verify')")
    public ResponseEntity<List<PortalFlexiblePlanDto>> plans() {
        return ResponseEntity.ok(plans.flexiblePlans());
    }
}
