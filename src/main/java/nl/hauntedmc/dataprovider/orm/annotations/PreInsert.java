package nl.hauntedmc.dataprovider.orm.annotations;

import java.lang.annotation.*;

/**
 * Called right before an entity is inserted (i.e., it's new in the DB).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PreInsert {}