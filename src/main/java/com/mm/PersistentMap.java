package com.mm;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Map persistente baseado em log NDJSON:
 *  - put/remove: append de linhas {"op":"put/remove","k":"...","v":"..."}
 *  - mantém ordem de inserção/atualização em memória (LinkedHashSet)
 *  - putWithLimit: ao exceder N, remove o mais antigo (FIFO das últimas operações de put)
 *  - compact(): reescreve apenas puts do snapshot, na ordem atual
 */
public final class PersistentMap implements Closeable {
  private static final ObjectMapper JSON = new ObjectMapper()
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private final Map<String, String> map = new ConcurrentHashMap<>();
  private final LinkedHashSet<String> order = new LinkedHashSet<>();
  private final Path file;
  private BufferedWriter out;

  private static final class Entry {
    public String op;   // "put" | "remove"
    public String k;
    public String v;

    @SuppressWarnings("unused")
    public Entry() {}
    
    Entry(String op, String k, String v){ this.op=op; this.k=k; this.v=v; }
  }

  public PersistentMap(Path file) throws IOException {
    this.file = file;
    Files.createDirectories(file.getParent());
    // Replay do log reconstruindo estado + ordem (put move para o fim)
    if (Files.exists(file)) {
      try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        String line;
        while ((line = br.readLine()) != null) {
          if (line.isBlank()) continue;
          Entry e = JSON.readValue(line, Entry.class);
          if ("put".equals(e.op)) {
            map.put(e.k, e.v);
            order.remove(e.k);
            order.add(e.k);
          } else if ("remove".equals(e.op)) {
            map.remove(e.k);
            order.remove(e.k);
          }
        }
      }
    }
    this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  public String get(String key){ return map.get(key); }
  public Map<String,String> snapshot(){ return Map.copyOf(map); }
  public int size(){ return map.size(); }

  /** put simples (sem limite) */
  public synchronized void put(String key, String value) throws IOException {
    putInternal(key, value);
  }

  /** put com limite de tamanho; evicta o mais antigo até caber */
  public synchronized void putWithLimit(String key, String value, int maxEntries) throws IOException {
    if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be > 0");
    putInternal(key, value);
    evictOldestWhileOver(maxEntries);
  }

  public synchronized void remove(String key) throws IOException {
    if (!map.containsKey(key)) return;
    map.remove(key);
    order.remove(key);
    write(new Entry("remove", key, null));
  }

  /** Compacta regravando apenas o snapshot atual, preservando a ordem. */
  public synchronized void compact() throws IOException {
    Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
    try (BufferedWriter bw = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

      for (String k : order) {
        String v = map.get(k);
        if (v != null) {
          bw.write(JSON.writeValueAsString(new Entry("put", k, v)));
          bw.write('\n');
        }
      }
      bw.flush();
    }
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    // reabrindo canal de escrita em append: fecha anterior e abre novo
    out.flush();
    try { out.close(); } catch (IOException ignore) {}
    out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);        
  }

  private void putInternal(String key, String value) throws IOException {
    map.put(key, value);
    order.remove(key);
    order.add(key);
    write(new Entry("put", key, value));
  }

  private void evictOldestWhileOver(int maxEntries) throws IOException {
    while (map.size() > maxEntries && !order.isEmpty()) {
      String oldest = order.iterator().next(); // primeiro = mais antigo
      map.remove(oldest);
      order.remove(oldest);
      write(new Entry("remove", oldest, null));
    }
  }

  private void write(Entry e) throws IOException {
    out.write(JSON.writeValueAsString(e));
    out.write('\n');
    out.flush();
  }

  @Override public void close() throws IOException { out.close(); }
}
