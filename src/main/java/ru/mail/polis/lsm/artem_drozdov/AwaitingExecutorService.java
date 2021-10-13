package ru.mail.polis.lsm.artem_drozdov;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class AwaitingExecutorService {

    private static final Logger LOG = LoggerFactory.getLogger(AwaitingExecutorService.class);

    private final ExecutorService service = Executors.newSingleThreadExecutor();
    private final AtomicReference<Future<?>> futureRef = new AtomicReference<>();

    public void execute(Runnable task) {
        synchronized (futureRef) {
            futureRef.set(service.submit(task));
        }
    }

    public void awaitTaskComplete() {
        synchronized (futureRef) {
            Future<?> future = futureRef.get();
            if (future != null) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    LOG.error("Failed to get future", e);
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    LOG.error("Failed to get future", e);
                }
            }
        }
    }

    public void shutdown() {
        service.shutdown();
        try {
            if (!service.awaitTermination(60, TimeUnit.SECONDS)) {
                LOG.error("Pool did not terminate");
            }
        } catch (InterruptedException e) {
            service.shutdownNow();
            LOG.error("Failed shutdown service", e);
            Thread.currentThread().interrupt();
        }
    }
}
