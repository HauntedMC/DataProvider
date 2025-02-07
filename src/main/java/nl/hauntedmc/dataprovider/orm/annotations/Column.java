package nl.hauntedmc.dataprovider.orm.annotations;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Column {
    String name(); // Column name in DB
    String type(); // SQL Data type
    boolean notNull() default false;
    boolean unique() default false;
    String defaultValue() default ""; // Default column value
}
