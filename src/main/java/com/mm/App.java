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

    // ========= WaboxApp webhook =========
    // Configure no painel do Wabox a sua hook URL, por ex. https://seu.dominio.com/wabox/hook
    // e defina WBX_TOKEN no ambiente para validar chamadas.
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

          // Exemplo: só processar mensagens de entrada "chat"
          if ("i".equalsIgnoreCase(msgDir) && "chat".equalsIgnoreCase(msgType) && msgText != null) {
            // Monte uma linha “bonita” pro seu feed
            // String pretty = (fromName != null ? fromName : (fromUid != null ? fromUid : "WA")) + ": " + msgText;
            String pretty = msgText;

            // Insere no KV como mais uma mensagem (mantendo as 50 últimas)
            // Usamos o seu gerador de IDs (ISO-8601 c/ espaçamento de 1ms)
            try {
              String k = Ids1ms.nextIsoWith1msGap();
              S.pmap.putWithLimit(k, pretty, S.MAX_ENTRIES);
            } catch (IOException e) {
              e.printStackTrace();
              // segue com 200 para não gerar reenvio do webhook
            }
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
