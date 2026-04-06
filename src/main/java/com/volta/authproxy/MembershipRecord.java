package com.volta.authproxy;

import java.util.UUID;

public record MembershipRecord(UUID id, UUID userId, UUID tenantId, String role, boolean active) {
}
