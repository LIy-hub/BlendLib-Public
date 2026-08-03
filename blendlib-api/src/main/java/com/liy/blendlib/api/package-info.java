/**
 * Stable, pure semantic BlendLib API.
 *
 * <p>This package is compatible within a BlendLib major version. It deliberately contains no
 * platform imports, GLB structures, asset bytes, mutable poses, bone matrices, render handles,
 * networking payloads, reflection hooks, or resource I/O. Platform authors who need a controlled
 * extension point must intentionally use {@code com.liy.blendlib.spi.experimental}; that fourth
 * compatibility axis is not part of this stable API promise.</p>
 */
package com.liy.blendlib.api;
