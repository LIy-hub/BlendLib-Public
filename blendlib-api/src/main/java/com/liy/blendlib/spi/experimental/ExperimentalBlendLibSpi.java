package com.liy.blendlib.spi.experimental;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controlled X1 SPI element whose compatibility is not part of the stable API promise.
 *
 * <p>Consumers should not adopt an annotated element accidentally. Provider authors must explicitly
 * choose the capability-protocol version range that they support.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.PACKAGE, ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface ExperimentalBlendLibSpi {
    /**
     * Returns the separately versioned capability protocol supported by the annotated element.
     *
     * @return protocol version string
     */
    String protocol() default "1.0.0";
}
