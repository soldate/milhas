package com.mm;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonMappingException;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;

public class App {

  public static void main(String[] args) throws Exception {

    // Porta: usa PORT de env/system prop, senão 8080
    int port = Integer.parseInt(System.getProperty(
        "PORT", System.getenv().getOrDefault("PORT", "8080")));

    // Inicia servidor HTTP
    var app = Javalin.create(config -> {
      config.staticFiles.add("/public", Location.CLASSPATH);
      config.spaRoot.addFile("/", "/public/index.html");
    }).start(port);

    // Fecha limpo
    app.events(ev -> ev.serverStopping(S::gracefulStop));

    // CORS para todas as rotas
    app.before(ctx -> {
      ctx.header("Access-Control-Allow-Origin", "*"); // em produção, prefira ecoar a origem específica
      ctx.header("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
      ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
      ctx.header("Access-Control-Max-Age", "86400"); // cacheia o preflight por 24h
    });

    // Healthcheck
    app.get("/healthz", ctx -> ctx.json(Map.of("ok", true)));

    // Responde o preflight
    app.options("/*", ctx -> ctx.status(204));

    // SET (put): {"v":"valor"} Obs: k é gerado automaticamente (timestamp)     
    app.post("/api/pmap", ctx -> {
      com.fasterxml.jackson.databind.JsonNode node;
      try {
        node = S.JSON.readTree(ctx.body());
      } catch (JsonMappingException parse) {
        ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("ok", false, "error", "invalid_json"));
        return;
      }

      var v = node.hasNonNull("v") ? node.get("v").asText() : null;
      if (v == null) {
        ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("ok", false, "error", "missing_value"));
        return;
      }

      try {
        String k_now = Ids1ms.nextIsoWith1msGap();
        S.pmap.putWithLimit(k_now, v, S.MAX_ENTRIES);
        ctx.status(HttpStatus.CREATED).json(Map.of("ok", true, "k", k_now));
      } catch (IOException e) {
        ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("ok", false, "error", "io_error"));
      }
    });

    // GET 1 chave
    app.get("/api/pmap/{k}", ctx -> {
      var k = ctx.pathParam("k");
      var v = S.pmap.get(k);
      if (v == null) {
        ctx.status(HttpStatus.NOT_FOUND).json(Map.of("ok", false, "error", "not_found"));
        return;
      }
      ctx.json(Map.of("ok", true, "k", k, "v", v));
    });

    // DELETE 1 chave
    app.delete("/api/pmap/{k}", ctx -> {
      var k = ctx.pathParam("k");
      if (S.pmap.get(k) == null) {
        ctx.status(HttpStatus.NOT_FOUND).json(Map.of("ok", false, "error", "not_found"));
        return;
      }
      try {
        S.pmap.remove(k);
        ctx.json(Map.of("ok", true));
      } catch (IOException e) {
        ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("ok", false, "error", "io_error"));
      }
    });

    // GET todas as chaves/valores
    app.get("/api/pmap", ctx -> {
      ctx.json(Map.of("ok", true, "items", S.pmap.snapshot()));
    });

  }
}
