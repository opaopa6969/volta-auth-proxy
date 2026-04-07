package org.unlaxer.infra.volta;

import java.util.UUID;

public record UserRecord(UUID id, String email, String displayName, String googleSub) {
}
