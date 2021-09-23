package ru.mail.polis.service.timatifey;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;

import ru.mail.polis.lsm.DAO;
import ru.mail.polis.lsm.Record;
import ru.mail.polis.service.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;

import static java.nio.charset.StandardCharsets.UTF_8;

public class BasicService extends HttpServer implements Service {
    private final static String V0 = "/v0";
    private final DAO dao;

    public BasicService(
            final int port,
            final DAO dao) throws IOException {
        super(from(port));
        this.dao = dao;
    }

    private static HttpServerConfig from(int port) {
        final HttpServerConfig config = new HttpServerConfig();
        final AcceptorConfig acceptor = new AcceptorConfig();
        acceptor.port = port;
        acceptor.reusePort = true;
        config.acceptors = new AcceptorConfig[]{acceptor};
        return config;
    }

    @Path(V0 + "/status")
    public Response status() {
        return Response.ok("I'm ok");
    }

    @Path(V0 + "/entity")
    public Response entity(
            final Request request,
            @Param(value = "id", required = true) final String id
    ) {
        if (id.isBlank()) {
            return new Response(Response.BAD_REQUEST, "Bad id".getBytes(UTF_8));
        }

        switch (request.getMethod()) {
            case Request.METHOD_GET:
                return get(id);
            case Request.METHOD_PUT:
                return put(id, request.getBody());
            case Request.METHOD_DELETE:
                return delete(id);
            default:
                return new Response(Response.METHOD_NOT_ALLOWED, "Wrong method".getBytes(UTF_8));
        }
    }

    private Response delete(String id) {
        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(UTF_8));
        dao.upsert(Record.tombstone(key));
        return new Response(Response.ACCEPTED);
    }

    private Response put(String id, byte[] body) {
        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(UTF_8));
        final ByteBuffer value = ByteBuffer.wrap(body);
        dao.upsert(Record.of(key, value));
        return new Response(Response.CREATED);
    }

    private static byte[] extractBytes(final ByteBuffer byteBuffer) {
        final byte[] result = new byte[byteBuffer.remaining()];
        byteBuffer.get(result);
        return result;
    }

    private Response get(String id) {
        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(UTF_8));
        final Iterator<Record> range = dao.range(key, null);
        if (range.hasNext()) {
            final Record first = range.next();
            return new Response(Response.OK, extractBytes(first.getKey()));
        } else {
            return new Response(Response.NOT_FOUND);
        }
    }
}
