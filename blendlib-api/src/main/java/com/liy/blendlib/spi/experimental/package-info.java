/**
 * Controlled, opt-in BlendLib provider SPI.
 *
 * <p>This package is <strong>Experimental</strong>. Its ABI and capability protocol are versioned
 * independently from the stable BlendLib API, asset schema/Profile, and platform adapter target. The
 * historical initial capability data-plane version is {@code 1.0.0}; the current host contract is
 * {@code 1.1.0}. A provider must opt in to the current bounded compatible range rather than assuming
 * that ordinary API compatibility implies SPI compatibility. Negotiation chooses capability metadata,
 * not a legacy throwable policy: metadata and lifecycle callbacks always run under the current host
 * rule that every {@link Error} is terminal and escapes by exact object after required cleanup.</p>
 *
 * <p>The package is adapter-controlled and must never reuse the v1 descriptor {@code extensions}
 * payload, semantic animation payload, renderer internals, or platform-private handles. Only platform
 * authors who deliberately opt in should depend on it.</p>
 */
package com.liy.blendlib.spi.experimental;
