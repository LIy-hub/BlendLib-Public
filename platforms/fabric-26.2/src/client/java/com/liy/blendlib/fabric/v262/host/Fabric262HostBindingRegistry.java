package com.liy.blendlib.fabric.v262.host;

import com.liy.blendlib.fabric.v262.diagnostic.Fabric262Diagnostic;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262DiagnosticCode;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262PlatformException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapter-owned immutable-binding registry for ordinary Fabric 26.2 host registrations.
 *
 * <p>The registry commits a semantic binding only after the owning Fabric 26.2 dispatcher has
 * installed its corresponding public native hook. This preserves a single acceptance point: a
 * failed native registration cannot leave metadata that falsely reports host support.</p>
 */
public final class Fabric262HostBindingRegistry implements AutoCloseable {
    private final ConcurrentHashMap<Fabric262HostTarget, Fabric262HostBinding> bindings = new ConcurrentHashMap<>();
    private final Object lifecycleMonitor = new Object();
    private volatile boolean closed;

    /**
     * Installs and records one translated binding exactly once per native host target.
     *
     * <p>The native installer runs while the duplicate fence is held and the binding is inserted
     * only after that installer returns normally. Fabric's public renderer/model registries do not
     * expose general unregistration; adapter close removes this registry's ownership and the item
     * dispatch mapping, while already registered native callbacks become inert through the closed
     * dispatcher.</p>
     *
     * @param binding immutable translated binding
     * @param nativeInstaller exact public Fabric registry installation for this binding
     * @throws Fabric262PlatformException for duplicate or terminal registry use
     */
    public void register(Fabric262HostBinding binding, Runnable nativeInstaller) {
        Fabric262HostBinding checked = Objects.requireNonNull(binding, "binding");
        Runnable checkedInstaller = Objects.requireNonNull(nativeInstaller, "nativeInstaller");
        synchronized (lifecycleMonitor) {
            if (closed) {
                throw new Fabric262PlatformException(Fabric262Diagnostic.error(
                        Fabric262DiagnosticCode.RUNTIME_CLOSED,
                        "Fabric 26.2 host bindings are closed"));
            }
            if (bindings.containsKey(checked.target())) {
                throw new Fabric262PlatformException(Fabric262Diagnostic.error(
                        Fabric262DiagnosticCode.HOST_TRANSLATION_FAILURE,
                        "A Fabric 26.2 host binding already exists for " + checked.target().registryId()));
            }
            checkedInstaller.run();
            bindings.put(checked.target(), checked);
        }
    }

    /**
     * Returns a deterministic immutable snapshot of dispatcher-installed host bindings.
     *
     * @return ascending native-host bindings
     */
    public List<Fabric262HostBinding> bindings() {
        return bindings.values().stream()
                .sorted(Comparator.comparing(binding -> binding.target().kind().name())
                        .thenComparing(binding -> binding.target().registryId().value()))
                .toList();
    }

    /**
     * Clears adapter-owned host binding state exactly once during adapter close.
     */
    @Override
    public void close() {
        synchronized (lifecycleMonitor) {
            if (closed) {
                return;
            }
            closed = true;
            bindings.clear();
        }
    }
}
