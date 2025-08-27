package com.mm;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/** Gera IDs baseados em Instant.now(), garantindo pelo menos 1 ms entre eles (por processo). */
public final class Ids1ms {
  private static final ReentrantLock LOCK = new ReentrantLock();
  private static long lastMs = -1L;

  /** Retorna um ID no formato ISO-8601 (Instant.toString()), sempre único e crescente. */
  public static String nextIsoWith1msGap() {
    LOCK.lock();
    try {
      long now = System.currentTimeMillis();

      if (lastMs < 0) {
        lastMs = now;
        return Instant.ofEpochMilli(now).toString();
      }

      long minNext = lastMs + 1L;
      if (now < minNext) {
        // espera o necessário para ter pelo menos 1 ms de distância
        try { Thread.sleep(minNext - now); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        now = System.currentTimeMillis();
      }
      // se por algum motivo o relógio não avançou o suficiente, força +1
      if (now <= lastMs) now = lastMs + 1L;

      lastMs = now;
      return Instant.ofEpochMilli(now).toString();
    } finally {
      LOCK.unlock();
    }
  }

  private Ids1ms() {}
}
