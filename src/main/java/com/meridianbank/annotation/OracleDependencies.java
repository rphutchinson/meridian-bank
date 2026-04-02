package com.meridianbank.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container annotation enabling multiple {@link OracleDependency} annotations
 * on the same element via {@code @Repeatable}.
 *
 * <p>Not used directly — Java inserts this automatically when multiple
 * {@code @OracleDependency} annotations are placed on a single element.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({
    ElementType.TYPE,
    ElementType.METHOD,
    ElementType.FIELD,
    ElementType.PARAMETER,
    ElementType.CONSTRUCTOR
})
public @interface OracleDependencies {

    OracleDependency[] value();
}
