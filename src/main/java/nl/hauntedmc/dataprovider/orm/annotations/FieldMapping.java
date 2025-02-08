package nl.hauntedmc.dataprovider.orm.annotations;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface FieldMapping {
    String name() default "";
    String columnDefinition() default "";
    boolean notNull() default false;
    // etc.
}
