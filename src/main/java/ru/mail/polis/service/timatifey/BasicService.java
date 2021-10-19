package ru.mail.polis.service.timatifey;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import ru.mail.polis.lsm.DAO;
import ru.mail.polis.lsm.Record;
import ru.mail.polis.service.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

public class BasicService extends HttpServer implements Service {

    private static final String V0 = "/v0";
    private final DAO dao;

    public BasicService(
            final int port,
            final DAO dao) throws IOException {
        super(from(port));
        this.dao = dao;
    }

    private static HttpServerConfig from(final int port) {
        final HttpServerConfig config = new HttpServerConfig();
        final AcceptorConfig acceptor = new AcceptorConfig();
        acceptor.port = port;
        acceptor.reusePort = true;
        config.acceptors = new AcceptorConfig[]{acceptor};
        return config;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
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
        new ThreadPoolExecutor(1, 1, 1, TimeUnit.MILLISECONDS, )
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

    private Response get(final String id) {
        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(UTF_8));
        final Iterator<Record> range = dao.range(key, DAO.nextKey(key));
        if (range.hasNext()) {
            final Record first = range.next();
            final ByteBuffer value = first.getValue();
            return new Response(Response.OK, extractBytes(value));
        } else {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
    }

    private Response put(final String id, byte[] body) {
        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(UTF_8));
        final ByteBuffer value = ByteBuffer.wrap(body);
        try {
            dao.upsert(Record.of(key, value));
            return new Response(Response.CREATED, Response.EMPTY);
        } catch (UncheckedIOException e) {
            return new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY);
        }
    }

    private Response delete(final String id) {
        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(UTF_8));
        try {
            dao.upsert(Record.tombstone(key));
            return new Response(Response.ACCEPTED, Response.EMPTY);
        } catch (UncheckedIOException e) {
            return new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY);
        }
    }

    private static byte[] extractBytes(final ByteBuffer byteBuffer) {
        final byte[] result = new byte[byteBuffer.remaining()];
        byteBuffer.get(result);
        return result;
    }

}
