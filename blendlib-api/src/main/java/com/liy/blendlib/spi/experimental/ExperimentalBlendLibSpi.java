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
 * choose the capability-protocol version range that they support. The current host line is
 * {@code [1.1.0, 1.2.0)}; it is deliberately independent from stable API SemVer, descriptor
 * Profile versions, and platform adapter targets. An annotation never authorizes implementation
 * imports, raw renderer handles, global adapter installation from ordinary consumer code, or
 * callback work in submit/animation/socket hot paths.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.PACKAGE, ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface ExperimentalBlendLibSpi {
    /** Current Experimental SPI host-contract protocol version. */
    String CURRENT_PROTOCOL = "1.1.0";

    /**
     * Returns the separately versioned current host-contract protocol supported by the annotated element.
     *
     * @return protocol version string
     */
    String protocol() default CURRENT_PROTOCOL;
}
