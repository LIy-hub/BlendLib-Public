package com.liy.blendlib.fabric.client.api;

/**
 * Non-forgeable capability consumed only while the client service facade installs its one
 * registry-backed lookup.
 *
 * <p>The sole permitted implementation has a private constructor and is retained inside
 * {@link BlendLibClientServices}; ordinary API consumers cannot construct, implement, or obtain
 * this marker. Reflection is outside this Java source/API trust boundary.</p>
 */
public sealed interface ClientModelLookupBootstrap permits BlendLibClientServices.ManagedLookupBootstrap {
}
