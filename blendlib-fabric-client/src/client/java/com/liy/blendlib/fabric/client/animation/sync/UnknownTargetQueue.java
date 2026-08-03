package com.liy.blendlib.fabric.client.animation.sync;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Bounded, short-lived latest-command queue for packets that arrive before their target is client-visible.
 *
 * <p>There is at most one queued command per target. Replacing it requires a newer sequence, and capacity pressure
 * evicts the oldest wait rather than growing with hostile or delayed packets.</p>
 */
public final class UnknownTargetQueue {
    public static final int DEFAULT_MAX_PENDING = 256;
    public static final long DEFAULT_TTL_TICKS = 20L;

    private final int maxPending;
    private final long ttlTicks;
    private final LinkedHashMap<ClientAnimationTarget, PendingCommand> pending = new LinkedHashMap<>();
    private long expiredDrops;
    private long capacityDrops;
    private long staleDrops;

    public UnknownTargetQueue() {
        this(DEFAULT_MAX_PENDING, DEFAULT_TTL_TICKS);
    }

    public UnknownTargetQueue(int maxPending, long ttlTicks) {
        if (maxPending <= 0) {
            throw new IllegalArgumentException("maxPending must be positive");
        }
        if (ttlTicks <= 0L) {
            throw new IllegalArgumentException("ttlTicks must be positive");
        }
        this.maxPending = maxPending;
        this.ttlTicks = ttlTicks;
    }

    /** Queues a target's newest command at the supplied client tick. */
    public EnqueueResult enqueue(ClientAnimationCommand command, long currentTick) {
        Objects.requireNonNull(command, "command");
        if (currentTick < 0L) {
            throw new IllegalArgumentException("currentTick must be non-negative");
        }
        PendingCommand existing = pending.get(command.target());
        if (existing != null && command.animation().sequence() <= existing.command.animation().sequence()) {
            staleDrops++;
            return EnqueueResult.STALE_DROPPED;
        }
        if (existing != null) {
            pending.remove(command.target());
        } else if (pending.size() >= maxPending) {
            Iterator<Map.Entry<ClientAnimationTarget, PendingCommand>> iterator = pending.entrySet().iterator();
            iterator.next();
            iterator.remove();
            capacityDrops++;
        }
        pending.put(command.target(), new PendingCommand(command, currentTick));
        return EnqueueResult.QUEUED;
    }

    /**
     * Retries known targets and expires waits that have consumed their full bounded TTL.
     */
    public RetryResult retry(long currentTick, Predicate<ClientAnimationTarget> targetAvailable) {
        if (currentTick < 0L) {
            throw new IllegalArgumentException("currentTick must be non-negative");
        }
        Objects.requireNonNull(targetAvailable, "targetAvailable");
        List<ClientAnimationCommand> ready = new ArrayList<>();
        int expiredThisRetry = 0;
        Iterator<Map.Entry<ClientAnimationTarget, PendingCommand>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ClientAnimationTarget, PendingCommand> entry = iterator.next();
            PendingCommand waiting = entry.getValue();
            if (currentTick - waiting.firstQueuedTick >= ttlTicks) {
                iterator.remove();
                expiredDrops++;
                expiredThisRetry++;
                continue;
            }
            if (targetAvailable.test(entry.getKey())) {
                ready.add(waiting.command);
                iterator.remove();
            }
        }
        return new RetryResult(List.copyOf(ready), expiredThisRetry);
    }

    /** Removes all queued state for an unloaded target before an id/position can be reused. */
    public void remove(ClientAnimationTarget target) {
        pending.remove(Objects.requireNonNull(target, "target"));
    }

    /** Clears all queued work at disconnect or dimension transition. */
    public void clear() {
        pending.clear();
    }

    public int size() {
        return pending.size();
    }

    public long expiredDrops() {
        return expiredDrops;
    }

    public long capacityDrops() {
        return capacityDrops;
    }

    public long staleDrops() {
        return staleDrops;
    }

    public enum EnqueueResult {
        QUEUED,
        STALE_DROPPED
    }

    /** Commands now resolvable by the client world plus the number discarded by the TTL. */
    public record RetryResult(List<ClientAnimationCommand> readyCommands, int expiredCommands) {
        public RetryResult {
            readyCommands = List.copyOf(Objects.requireNonNull(readyCommands, "readyCommands"));
            if (expiredCommands < 0) {
                throw new IllegalArgumentException("expiredCommands must be non-negative");
            }
        }
    }

    private record PendingCommand(ClientAnimationCommand command, long firstQueuedTick) {
        private PendingCommand {
            command = Objects.requireNonNull(command, "command");
            if (firstQueuedTick < 0L) {
                throw new IllegalArgumentException("firstQueuedTick must be non-negative");
            }
        }
    }
}
