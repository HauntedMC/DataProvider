package nl.hauntedmc.dataprovider.orm.annotations;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Id {
    boolean autoGenerate() default true;
}
