package net.programmierecke.radiodroid2.utils;

import android.util.Log;

import net.programmierecke.radiodroid2.BuildConfig;

public class RateLimiter {
    private final int limit;
    private final long fullReplenishTime;

    private double available = 0;

    private long lastTime;

    public RateLimiter(int limit, long fullReplenishTime) {
        this.limit = limit;
        this.fullReplenishTime = fullReplenishTime;

        available = limit;
        lastTime = System.currentTimeMillis();
    }

    public boolean allowed() {
        final long now = System.currentTimeMillis();

        available += Math.abs(now - lastTime) * (1.0 / fullReplenishTime) * limit;
        if (available > limit) {
            available = limit;
        }

        if (available < 1.0) {
            return false;
        } else {
            available--;
            lastTime = now;
            return true;
        }
    }
}
