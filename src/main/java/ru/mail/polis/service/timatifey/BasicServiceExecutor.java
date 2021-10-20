package ru.mail.polis.service.timatifey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class BasicServiceExecutor {

    private static final int DEFAULT_EXECUTOR_THREAD_POOL_SIZE = 4;
    private static final int DEFAULT_QUEUE_CAPACITY = 5_000_000;

    private static final Logger LOG = LoggerFactory.getLogger(BasicServiceExecutor.class);

    private final ThreadPoolExecutor executor;

    public BasicServiceExecutor(int threadPoolSize, int queueCapacity) {
        this.executor = new ThreadPoolExecutor(
                threadPoolSize,
                threadPoolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity)
        );
    }

    public BasicServiceExecutor(int threadPoolSize) {
        this(threadPoolSize, DEFAULT_QUEUE_CAPACITY);
    }

    public BasicServiceExecutor() {
        this(DEFAULT_EXECUTOR_THREAD_POOL_SIZE, DEFAULT_QUEUE_CAPACITY);
    }

    public void execute(Runnable runnable) {
        executor.execute(runnable);
    }

    public boolean remainingCapacityIsEmpty() {
        if (executor.getQueue().remainingCapacity() == 0) {
            synchronized (this) {
                if (executor.getQueue().remainingCapacity() == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                LOG.error("Pool did not terminate");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            LOG.error("Failed shutdown service", e);
            Thread.currentThread().interrupt();
        }
    }
}
