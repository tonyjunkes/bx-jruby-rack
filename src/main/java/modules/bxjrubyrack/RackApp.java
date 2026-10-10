package modules.bxjrubyrack;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import ortus.boxlang.runtime.types.*;

/** Fixed, thread-affine workers. A lease includes input preparation, output and cleanup. */
public final class RackApp implements AutoCloseable {
    private final RackConfig config;
    private final CoreBridge core;
    private final BlockingQueue<Job> queue;
    private final Semaphore admission;
    private final List<Thread> threads = new ArrayList<>();
    private final AtomicInteger active = new AtomicInteger();
    private final LongAdder requests = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder bytes = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder inputBytes = new LongAdder();
    private final LongAdder queueWaitNanos = new LongAdder();
    private final LongAdder requestNanos = new LongAdder();
    private volatile String state = "NEW";
    private volatile IStruct versions;
    private long bootMs;
    private final java.util.function.Consumer<RackApp> unregister;
    private static final System.Logger LOG = System.getLogger("bx-jruby-rack");
    private static final String BRIDGE;

    static {
        try (var stream = RackApp.class.getResourceAsStream("/modules/bxjrubyrack/bridge.rb")) {
            BRIDGE = new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    /**
     * Allocates the bounded queue and admission semaphore without starting Ruby runtimes.
     *
     * @param config the application configuration
     * @param core the validated public core bridge
     * @param unregister the callback removing this app from routing
     */
    RackApp(RackConfig config, CoreBridge core, java.util.function.Consumer<RackApp> unregister) {
        this.config = config;
        this.core = core;
        this.unregister = unregister;
        queue = new LinkedBlockingQueue<>(config.workers() + config.queueCapacity());
        admission = new Semaphore(config.workers() + config.queueCapacity(), true);
    }

    /**
     * Starts every thread-affine worker and waits for boot. Failure drains all workers before propagating the
     * cause.
     */
    void boot() {
        state = "STARTING";
        long start = System.nanoTime();
        var boots = new ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < config.workers(); i++) {
            var ready = new CompletableFuture<Void>();
            boots.add(ready);
            var thread = new Thread(() -> worker(ready), "bx-rack-" + config.id() + "-" + i);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
        try {
            CompletableFuture.allOf(boots.toArray(CompletableFuture[]::new)).join();
            bootMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            synchronized (this) {
                if (!state.equals("STARTING"))
                    throw new RackException("BOOT_FAILED", 500, "Rack worker stopped during startup");
                state = "READY";
            }
        } catch (RuntimeException error) {
            state = "FAILED";
            awaitClosed();
            throw error;
        }
    }

    /**
     * Owns a core session from boot through request execution and cleanup on the same thread.
     *
     * @param ready the future signalled when the worker boots or fails
     */
    private void worker(CompletableFuture<Void> ready) {
        try (var session = core.session(config)) {
            if (!java.nio.file.Files.isRegularFile(config.rackup()))
                throw RackConfig.config("Missing rackup file: " + config.rackup());
            var boot = session.eval(
                BRIDGE,
                Map.of(
                    "bx_boot",
                    Map.of("bundler", config.bundler(), "rackup", config.rackup().toString())
                )
            );
            log(boot);
            if (!(boot.get("value") instanceof IStruct loaded) || !loaded.containsKey("rack"))
                throw new RackException("BOOT_FAILED", 500, "Ruby bootstrap exited without creating a Rack app");
            versions = loaded;
            ready.complete(null);
            while (state.equals("STARTING") || state.equals("READY")) {
                Job job = queue.poll(100, TimeUnit.MILLISECONDS);
                if (job == null) continue;
                synchronized (this) {
                    if (!state.equals("STARTING") && !state.equals("READY")) {
                        job.cancel();
                        job.result.completeExceptionally(unavailable("APP_CLOSED"));
                        admission.release();
                        continue;
                    }
                    if (!job.begin()) {
                        admission.release();
                        continue;
                    }
                }
                active.incrementAndGet();
                queueWaitNanos.add(System.nanoTime() - job.submitted);
                long started = System.nanoTime();
                try {
                    job.work.run(session);
                    requests.increment();
                    job.result.complete(null);
                } catch (Throwable error) {
                    failures.increment();
                    job.result.completeExceptionally(error);
                } finally {
                    requestNanos.add(System.nanoTime() - started);
                    active.decrementAndGet();
                    admission.release();
                }
            }
        } catch (Throwable error) {
            ready.completeExceptionally(error);
            synchronized (this) {
                if (state.equals("STARTING") || state.equals("READY")) {
                    state = "FAILED";
                    drain();
                }
            }
            LOG.log(System.Logger.Level.ERROR, "Rack worker failed: " + config.id(), error);
        }
    }

    /**
     * Snapshots state, versions, admission counts and cumulative metrics. A drained pool becomes CLOSED only
     * after its threads terminate.
     *
     * @return the app status struct, excluding environment values and request bodies
     */
    public IStruct status() {
        if (state.equals("DRAINING") && threads.stream().noneMatch(Thread::isAlive)) state = "CLOSED";
        return Struct.of(
            "id",
            config.id(),
            "scope",
            config.scope(),
            "state",
            state,
            "workers",
            threads.size(),
            "active",
            active.get(),
            "queued",
            queue.size(),
            "requests",
            requests.sum(),
            "failures",
            failures.sum(),
            "rejected",
            rejected.sum(),
            "available",
            state.equals("READY") ? threads.size() - active.get() : 0,
            "requestBytes",
            inputBytes.sum(),
            "responseBytes",
            bytes.sum(),
            "queueWaitMs",
            queueWaitNanos.sum() / 1000000.0,
            "requestMs",
            requestNanos.sum() / 1000000.0,
            "bootMs",
            bootMs,
            "versions",
            versions == null ? null : Struct.fromMap(versions)
        );
    }

    /**
     * Exposes the app's immutable configuration to routing and admission code.
     *
     * @return the resolved configuration
     */
    RackConfig config() {
        return config;
    }

    /**
     * Copies resolved app settings for explicit tasks or close-and-create replacement. Unlike status,
     * this snapshot includes environment values and should not be exposed through a public endpoint.
     *
     * @return a detached configuration struct accepted by jrubyRack and jrubyRackTask
     */
    public IStruct configuration() {
        return config.toStruct();
    }

    /**
     * Holds worker ownership through spooling, Ruby dispatch, response cleanup and removal of servlet
     * bindings.
     *
     * @param request the request and its raw input and response sink
     * @throws RackException if admission fails or the input exceeds configured limits
     */
    void dispatch(RackRequest request) {
        execute(session -> {
            try (var body = RequestBody.read(request.input(), request.contentLength(), config)) {
                inputBytes.add(body.length());
                var sink = request.sink();
                RuntimeException failure = null;
                try {
                    var result = session.eval(
                        "BxRack.dispatch(bx_app, bx_meta, bx_input, bx_sink)",
                        Map.of("bx_meta", request.metadata(), "bx_input", body, "bx_sink", sink)
                    );
                    log(result);
                    if (result.get("value") != null)
                        throw new RackException("BRIDGE_FAILED", 500, "Ruby response exited before completion");
                } catch (RuntimeException error) {
                    failure = error;
                    throw error;
                } finally {
                    bytes.add(sink.bytes());
                    try {
                        session.clearRequestBindings();
                    } catch (RuntimeException error) {
                        if (failure == null) throw error;
                        failure.addSuppressed(error);
                    }
                }
            }
        });
    }

    /**
     * Admits bounded work and waits for completion. Timeout cancels only unstarted jobs; active Ruby work
     * retains ownership if the caller is interrupted.
     *
     * @param work the operation executed while holding worker ownership
     * @throws RackException if the app is closed, the queue is full, or unstarted work times out
     */
    private void execute(Work work) {
        Job job;
        synchronized (this) {
            if (!state.equals("READY")) throw unavailable("APP_CLOSED");
            if (!admission.tryAcquire()) throw unavailable("QUEUE_FULL");
            job = new Job(work);
            queue.add(job);
        }
        boolean interrupted = false;
        try {
            try {
                if (!job.started.await(config.acquireTimeoutMs(), TimeUnit.MILLISECONDS) && job.cancel()) {
                    if (queue.remove(job)) admission.release();
                    throw unavailable("ACQUIRE_TIMEOUT");
                }
            } catch (InterruptedException error) {
                interrupted = true;
                if (job.cancel()) {
                    if (queue.remove(job)) admission.release();
                    throw unavailable("REQUEST_INTERRUPTED");
                }
            }
            // Once started, preserve ownership until Ruby and cleanup actually finish.
            for (; ; ) {
                try {
                    job.result.get();
                    return;
                } catch (InterruptedException error) {
                    interrupted = true;
                } catch (ExecutionException error) {
                    if (error.getCause() instanceof RuntimeException runtime) throw runtime;
                    throw new IllegalStateException("Rack request failed", error.getCause());
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Counts an admission rejection and creates a service-unavailable failure.
     *
     * @param code the stable diagnostic error code
     * @return a Rack exception with HTTP status 503
     */
    private RackException unavailable(String code) {
        rejected.increment();
        return new RackException(code, 503, "Rack app is unavailable");
    }

    /**
     * Idempotently removes routing and admission, cancels queued work and waits up to the grace budget.
     * Active workers remain owned and DRAINING beyond that budget.
     */
    @Override
    public void close() {
        drain();
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.shutdownGraceMs());
        for (Thread thread : threads) {
            long left = end - System.nanoTime();
            if (left <= 0) break;
            try {
                thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        status();
    }

    /**
     * Removes the route and rejects queued jobs without interrupting active workers.
     */
    private synchronized void drain() {
        if (state.equals("CLOSED") || state.equals("DRAINING")) return;
        if (!state.equals("FAILED")) state = "DRAINING";
        unregister.accept(this);
        Job job;
        while ((job = queue.poll()) != null) {
            if (job.cancel()) job.result.completeExceptionally(unavailable("APP_CLOSED"));
            admission.release();
        }
    }

    /**
     * Drains and waits without an execution deadline for every worker to terminate, restoring caller
     * interruption afterward.
     */
    void awaitClosed() {
        drain();
        boolean interrupted = false;
        for (Thread thread : threads)
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
        if (!state.equals("FAILED")) state = "CLOSED";
        if (interrupted) Thread.currentThread().interrupt();
    }

    /**
     * Logs completed bounded Ruby captures, using warning level for stderr.
     *
     * @param capture the completed stdout/stderr capture
     */
    static void log(IStruct capture) {
        for (String field : List.of("stdout", "stderr")) {
            Object text = capture.get(field);
            if (text != null && !text.toString().isBlank())
                LOG.log(
                    field.equals("stderr") ? System.Logger.Level.WARNING : System.Logger.Level.INFO,
                    text.toString()
                );
        }
    }

    @FunctionalInterface
    private interface Work {
        /**
         * Executes one request operation on the worker thread owning the session.
         *
         * @param session the worker-owned core session
         * @throws Exception if worker execution fails
         */
        void run(CoreBridge.Session session) throws Exception;
    }

    private static final class Job {
        final long submitted = System.nanoTime();
        final Work work;
        final CompletableFuture<Void> result = new CompletableFuture<>();
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicInteger state = new AtomicInteger();

        /**
         * Creates a queued operation with a start latch and completion future.
         *
         * @param work the operation executed while holding worker ownership
         */
        Job(Work work) {
            this.work = work;
        }

        /**
         * Atomically claims an unstarted job and wakes its waiting caller.
         *
         * @return true if the job was claimed before cancellation
         */
        boolean begin() {
            boolean accepted = state.compareAndSet(0, 1);
            started.countDown();
            return accepted;
        }

        /**
         * Atomically cancels only an unstarted job and wakes its waiting caller.
         *
         * @return true if cancellation won the admission race
         */
        boolean cancel() {
            boolean cancelled = state.compareAndSet(0, 2);
            started.countDown();
            return cancelled;
        }
    }
}
