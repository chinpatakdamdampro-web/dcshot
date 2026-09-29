package dev.discordshot.discord;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe per-user cooldown, checked before a screenshot job is even queued. Backed by
 * a {@link ConcurrentHashMap} since JDA can dispatch multiple interaction events for
 * different users concurrently.
 */
public final class CooldownTracker {

    private final ConcurrentHashMap<Long, Long> lastUseAtMillis = new ConcurrentHashMap<>();
    private final long cooldownMillis;

    public CooldownTracker(int cooldownSeconds) {
        this.cooldownMillis = TimeUnit.SECONDS.toMillis(Math.max(0, cooldownSeconds));
    }

    /** Returns 0 if the user may go ahead now, otherwise the whole seconds they must still wait. */
    public long secondsRemaining(long userId) {
        if (cooldownMillis <= 0) {
            return 0;
        }
        Long last = lastUseAtMillis.get(userId);
        if (last == null) {
            return 0;
        }
        long remainingMillis = cooldownMillis - (System.currentTimeMillis() - last);
        return remainingMillis <= 0 ? 0 : (remainingMillis + 999) / 1000;
    }

    public void recordUse(long userId) {
        lastUseAtMillis.put(userId, System.currentTimeMillis());
    }
}
