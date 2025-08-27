package com.mm;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;

public class S {

  static final PersistentMap pmap;
  static final ObjectMapper JSON = new ObjectMapper();  
  static final int MAX_ENTRIES = 50;
  static final AtomicBoolean STOPPED = new AtomicBoolean(false);

  static void gracefulStop() {
    if (!STOPPED.compareAndSet(false, true)) return; // roda só uma vez

    // 1) para o scheduler
    SCHED.shutdown();
    try {
      if (!SCHED.awaitTermination(5, TimeUnit.SECONDS)) SCHED.shutdownNow();
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      SCHED.shutdownNow();
    }

    // 2) fecha o pmap
    try { if (pmap != null) pmap.close(); } catch (IOException ignored) {}
  }  

  static final ScheduledExecutorService SCHED =
    Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "mm-scheduler");
      t.setDaemon(true); // não impede o processo de encerrar
      return t;
    });

  static {
    // Abre o pmap em data/pmap.ndjson
    try {
      pmap = new PersistentMap(Paths.get("data", "pmap.ndjson"));
    } catch (IOException e) {
      throw new RuntimeException("Falha ao abrir PersistentMap", e);
    }

    // compactar o NDJSON a cada 10 minutos
    SCHED.scheduleWithFixedDelay(() -> {
      try { pmap.compact(); } catch (IOException e) { e.printStackTrace(); }
    }, 10, 10, TimeUnit.MINUTES);    

    // desligar limpo quando o servidor parar
    Runtime.getRuntime().addShutdownHook(new Thread(S::gracefulStop));    
  }    
}
