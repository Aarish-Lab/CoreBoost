package com.coreboost.client;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FastMath {
    private static final int SIZE = 1 << 14, MASK = SIZE - 1;
    private static final float SCALE = SIZE / (float)(Math.PI * 2.0D);
    private static final float[] SIN = new float[SIZE];
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile boolean ready;
    private FastMath() { }

    public static void initializeAsync(Executor workers) {
        if (!STARTED.compareAndSet(false, true)) return;
        try {
            workers.execute(new Runnable() {
                public void run() {
                    for (int i = 0; i < SIZE; i++) SIN[i] = (float)Math.sin(i * Math.PI * 2.0D / SIZE);
                    ready = true;
                }
            });
        } catch (RejectedExecutionException rejected) {
            STARTED.set(false);
        }
    }

    public static float sin(float angle) {
        return ready ? SIN[((int)(angle * SCALE)) & MASK] : (float)Math.sin(angle);
    }
    public static float cos(float angle) {
        return ready ? SIN[(((int)(angle * SCALE)) + (SIZE >> 2)) & MASK] : (float)Math.cos(angle);
    }
}

