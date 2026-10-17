package modules.bxjrubyrack;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** A local observation, not a load-test SLA or production capacity claim. */
class BenchmarkTest {
    /**
     * Samples used JVM heap after repeated garbage-collection requests and brief settling waits.
     *
     * @return the process's observed used heap in bytes
     * @throws InterruptedException if the sampling wait is interrupted
     */
    static long usedHeap() throws InterruptedException {
        var runtime = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(200);
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     * Records warmed custom Rack throughput and heap observations for one and two workers without claiming production capacity.
     *
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    @Test
    void observeOneAndTwoWorkerPools() throws Exception {
        InstalledHttpTest.start();
        var observations = new ArrayList<String>();
        try {
            for (int workers : List.of(1, 2)) {
                long before = usedHeap();
                Object app = InstalledHttpTest.createApp("bench" + workers, Map.of("workers", workers));
                try {
                    String path = "/bench" + workers + "/state";
                    for (int i = 0; i < 256; i++)
                        assertEquals(200, InstalledHttpTest.request(path, "GET", new byte[0]).statusCode());
                    long heap = usedHeap();
                    long start = System.nanoTime();
                    for (int batch = 0; batch < 400; batch++) {
                        var pending = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<byte[]>>>();
                        for (int i = 0; i < 8; i++)
                            pending.add(
                                InstalledHttpTest.client.sendAsync(
                                    HttpRequest.newBuilder(URI.create(InstalledHttpTest.base + path)).GET().build(),
                                    HttpResponse.BodyHandlers.ofByteArray()
                                )
                            );
                        for (var response : pending) assertEquals(200, response.get().statusCode());
                    }
                    double seconds = (System.nanoTime() - start) / 1e9;
                    observations.add(
                        "{\"workers\":"
                            + workers
                            + ",\"bootMs\":"
                            + InstalledHttpTest.status(app).get("bootMs")
                            + ",\"usedHeapBytes\":"
                            + heap
                            + ",\"usedHeapDeltaBytes\":"
                            + (heap - before)
                            + ",\"requests\":3200,\"concurrency\":8,\"seconds\":"
                            + seconds
                            + ",\"requestsPerSecond\":"
                            + 3200 / seconds
                            + "}"
                    );
                } finally {
                    InstalledHttpTest.invoke(app, "close");
                }
            }
            Files.writeString(Path.of("build/benchmark.json"), "[" + String.join(",", observations) + "]\n");
        } finally {
            InstalledHttpTest.stop();
        }
    }
}
