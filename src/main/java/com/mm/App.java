package com.mm;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

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
        if (ctx.path().startsWith("/m")) {
          ctx.header("X-Robots-Tag", "noindex, nofollow, noarchive, nosnippet");
        } else {
          ctx.header("Access-Control-Allow-Origin", "*"); // em produção, prefira ecoar a origem específica
          ctx.header("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
          ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
          ctx.header("Access-Control-Max-Age", "86400"); // cacheia o preflight por 24h
        }
    });

      app.before(ctx -> {
      });    

    // Healthcheck
    app.get("/healthz", ctx -> ctx.json(Map.of("ok", true)));

    // Responde o preflight
    app.options("/*", ctx -> ctx.status(204));

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

    app.post("/wabox/hook", ctx -> {
      // 1) valida token
      String expected = System.getenv().getOrDefault("WBX_TOKEN", "");
      String token = ctx.formParam("token");
      if (expected.isEmpty() || token == null || !token.equals(expected)) {
        ctx.status(403).json(Map.of("ok", false, "error", "forbidden"));
        return;
      }

      // 2) identifica evento
      String event = ctx.formParam("event");
      if (event == null) {
        ctx.status(400).json(Map.of("ok", false, "error", "missing_event"));
        return;
      }

      switch (event) {
        case "message" -> {
          // Campos conforme doc (POST form): contact[...], message[...], ack, etc.
          // https://www.waboxapp.com/assets/doc/waboxapp-API-v3.pdf (Events notification)
          String fromUid   = ctx.formParam("contact[uid]");
          String fromName  = ctx.formParam("contact[name]");
          String msgType   = ctx.formParam("message[type]");       // chat, image, ...
          String msgDir    = ctx.formParam("message[dir]");        // i/o
          String msgUid    = ctx.formParam("message[uid]");        // ID da mensagem no IM
          String msgCuid   = ctx.formParam("message[cuid]");       // seu custom id (se você enviou)
          String msgText   = ctx.formParam("message[body][text]"); // para tipo chat
          String ack       = ctx.formParam("message[ack]");
          String dtm       = ctx.formParam("message[dtm]");        // epoch seconds

          if ("i".equalsIgnoreCase(msgDir) && "chat".equalsIgnoreCase(msgType) && msgText != null) {
            ObjectNode v = S.JSON.createObjectNode();
            v.put("text", msgText);
            if (fromName != null) v.put("name", fromName);
            if (fromUid  != null) v.put("wa", S.normalizeWa(fromUid));
            String k = Ids1ms.nextIsoWith1msGap();
            S.pmap.putWithLimit(k, S.JSON.writeValueAsString(v), S.MAX_ENTRIES);
          }

          // Responda rápido. O Wabox só precisa de 200 OK.
          ctx.json(Map.of("ok", true));
        }

        case "ack" -> {
          // ACK de mensagens que VOCÊ enviou via API (útil se um dia for responder pelo seu bot)
          // Doc lista: event=ack, token, uid/muid, cuid, ack
          String cuid   = ctx.formParam("cuid"); // seu custom id
          String muid   = ctx.formParam("muid"); // id no IM (algumas versões mandam "uid")
          if (muid == null) muid = ctx.formParam("uid"); // fallback, pois já vi ambos
          String ack    = ctx.formParam("ack");
          // Se quiser, persista status do envio (opcional)
          // KV.putWithLimit("ack:"+Instant.now(), "cuid="+cuid+" ack="+ack, MAX_KV_ENTRIES);
          ctx.json(Map.of("ok", true));
        }

        default -> {
          // eventos não tratados
          ctx.status(204);
        }
      }
    });


  }
}
