/**
 * Controlled, opt-in BlendLib provider SPI.
 *
 * <p>This package is <strong>Experimental</strong>. Its ABI and capability protocol are versioned
 * independently from the stable BlendLib API, asset schema/Profile, and platform adapter target. The
 * initial protocol is {@code 1.0.0}; a provider must negotiate a bounded compatible range rather than
 * assuming that ordinary API compatibility implies SPI compatibility.</p>
 *
 * <p>The package is adapter-controlled and must never reuse the v1 descriptor {@code extensions}
 * payload, semantic animation payload, renderer internals, or platform-private handles. Only platform
 * authors who deliberately opt in should depend on it.</p>
 */
package com.liy.blendlib.spi.experimental;
