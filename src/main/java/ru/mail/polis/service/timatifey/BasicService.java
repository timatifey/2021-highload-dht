package ru.mail.polis.service.timatifey;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.lsm.DAO;
import ru.mail.polis.lsm.Record;
import ru.mail.polis.service.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Iterator;

import static java.nio.charset.StandardCharsets.UTF_8;

public class BasicService extends HttpServer implements Service {

    private static final Logger LOG = LoggerFactory.getLogger(BasicService.class);

    private static final String V0 = "/v0";
    private static final String END_POINT_ENTITY = V0 + "/entity";
    private static final String END_POINT_STATUS = V0 + "/status";
    private static final String PARAM_ID = "id=";

    private final DAO dao;
    private final BasicServiceExecutor executorService = new BasicServiceExecutor();

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
    public synchronized void stop() {
        executorService.shutdown();
        super.stop();
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        if (executorService.remainingCapacityIsEmpty()) {
            sendResponse(session, serviceUnavailableResponse());
            return;
        }
        executorService.execute(() -> {
            final Response response = performRequest(request);
            sendResponse(session, response);
        });
    }

    private static void sendResponse(HttpSession session, Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            LOG.error("Can't send response", e);
        }
    }

    private Response performRequest(Request request) {
        switch (request.getPath()) {
            case END_POINT_ENTITY:
                final String id = request.getParameter(PARAM_ID);
                if (id == null || id.isBlank()) {
                    return new Response(Response.BAD_REQUEST, "Bad id".getBytes(UTF_8));
                }
                final ByteBuffer key = ByteBuffer.wrap(id.getBytes(UTF_8));
                return entity(request, key);
            case END_POINT_STATUS:
                return status();
            default:
                return defaultResponse();
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(defaultResponse());
    }

    private static Response defaultResponse() {
        return new Response(Response.BAD_REQUEST, Response.EMPTY);
    }

    private static Response serviceUnavailableResponse() {
        return new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY);
    }

    public Response status() {
        return Response.ok("I'm ok");
    }

    public Response entity(final Request request, final ByteBuffer key) {
        switch (request.getMethod()) {
            case Request.METHOD_GET:
                return get(key);
            case Request.METHOD_PUT:
                return put(key, request.getBody());
            case Request.METHOD_DELETE:
                return delete(key);
            default:
                return new Response(Response.METHOD_NOT_ALLOWED, "Wrong method".getBytes(UTF_8));
        }
    }

    private Response get(final ByteBuffer key) {
        final Iterator<Record> range = dao.range(key, DAO.nextKey(key));
        if (range.hasNext()) {
            final Record first = range.next();
            final ByteBuffer value = first.getValue();
            return new Response(Response.OK, extractBytes(value));
        } else {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
    }

    private Response put(final ByteBuffer key, byte[] body) {
        final ByteBuffer value = ByteBuffer.wrap(body);
        try {
            dao.upsert(Record.of(key, value));
            return new Response(Response.CREATED, Response.EMPTY);
        } catch (UncheckedIOException e) {
            return new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY);
        }
    }

    private Response delete(final ByteBuffer key) {
        try {
            dao.upsert(Record.tombstone(key));
            return new Response(Response.ACCEPTED, Response.EMPTY);
        } catch (UncheckedIOException e) {
            return serviceUnavailableResponse();
        }
    }

    private static byte[] extractBytes(final ByteBuffer byteBuffer) {
        final byte[] result = new byte[byteBuffer.remaining()];
        byteBuffer.get(result);
        return result;
    }

}
