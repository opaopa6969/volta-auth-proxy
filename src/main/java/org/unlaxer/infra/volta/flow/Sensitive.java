package org.unlaxer.infra.volta.flow;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as containing PII or secrets.
 * Fields annotated with @Sensitive are redacted in audit logs (auth_flow_transitions.context_snapshot).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Sensitive {
}
