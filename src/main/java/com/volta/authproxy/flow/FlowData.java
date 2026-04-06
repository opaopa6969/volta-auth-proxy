package com.volta.authproxy.flow;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as FlowContext data with a stable serialization alias.
 * Alias is used instead of FQCN for JSON persistence (class rename resistant).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FlowData {
    String value();
}
