package org.unlaxer.infra.volta;

import java.time.Instant;
import java.util.UUID;

public record TenantRecord(UUID id, String name, String slug, boolean mfaRequired, Instant mfaGraceUntil) {
}
