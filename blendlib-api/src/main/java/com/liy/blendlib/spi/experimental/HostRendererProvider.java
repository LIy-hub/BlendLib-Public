package com.liy.blendlib.spi.experimental;

import com.liy.blendlib.api.HostKind;
import java.util.Set;

/** Controlled provider of host-renderer compatibility metadata. */
@ExperimentalBlendLibSpi
public interface HostRendererProvider extends BlendProvider {
    /**
     * Returns supported semantic host categories, not platform renderer objects.
     *
     * @return immutable or snapshot-safe host-kind set
     */
    Set<HostKind> supportedHostKinds();
}
