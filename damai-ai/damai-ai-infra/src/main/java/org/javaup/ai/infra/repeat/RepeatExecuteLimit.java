package org.javaup.ai.infra.repeat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Target(value = {ElementType.TYPE, ElementType.METHOD})
@Retention(value = RetentionPolicy.RUNTIME)
public @interface RepeatExecuteLimit {

    String name() default "";

    String[] keys();

    long duration();

    TimeUnit timeUnit() default TimeUnit.SECONDS;

    String message() default "request is being processed, please try again later";
}
