package nl.hauntedmc.dataprovider.orm.annotations;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ForeignKey {
    String referenceTable(); // The referenced table
    String referenceColumn(); // The referenced column
    boolean cascadeDelete() default false;
}
