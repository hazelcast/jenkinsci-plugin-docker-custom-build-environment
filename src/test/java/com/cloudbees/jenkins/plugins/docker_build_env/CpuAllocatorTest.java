package com.cloudbees.jenkins.plugins.docker_build_env;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CpuAllocatorTest {

    private final String daemon = UUID.randomUUID().toString();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Set<Integer> used = Collections.synchronizedSet(new TreeSet<Integer>());

    @After
    public void stopExecutor() throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    public void parsesSparseCpuIdsAndRanges() throws Exception {
        assertEquals(cpus(2, 4, 5, 6, 12), CpuAllocator.parse(" 2,4-6,12,5 "));
        assertTrue(CpuAllocator.parse("").isEmpty());
    }

    @Test
    public void rejectsMalformedCpuSets() throws Exception {
        for (String invalid : new String[]{"1,", "4-2", "-1", "one", "1--2", "2147483648"}) {
            try {
                CpuAllocator.parse(invalid);
                fail("Accepted CPU set: " + invalid);
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("Invalid Docker CPU"));
            }
        }
    }

    @Test
    public void keepsSelectionAndContainerStartAtomic() throws Exception {
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch finishStart = new CountDownLatch(1);
        CountDownLatch secondAttempt = new CountDownLatch(1);
        AtomicInteger snapshots = new AtomicInteger();
        CpuAllocator.CpuUsage usage = () -> {
            snapshots.incrementAndGet();
            return snapshot();
        };
        Future<String> first = executor.submit(() -> CpuAllocator.run(daemon, cpus(2, 4, 8), 2, usage, selected -> {
            starting.countDown();
            assertTrue(finishStart.await(10, TimeUnit.SECONDS));
            used.addAll(selected);
            return selected.toString();
        }, () -> fail("First build should fit")));
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        Future<String> second = executor.submit(() -> {
            secondAttempt.countDown();
            return CpuAllocator.run(daemon, cpus(2, 4, 8), 1, usage, this::start,
                    () -> fail("Both builds should fit"));
        });
        assertTrue(secondAttempt.await(10, TimeUnit.SECONDS));
        try {
            second.get(200, TimeUnit.MILLISECONDS);
            fail("Second start completed before the first container was recorded");
        } catch (java.util.concurrent.TimeoutException expected) {
            assertEquals(1, snapshots.get());
        } finally {
            finishStart.countDown();
        }
        assertEquals("[2, 4]", first.get(10, TimeUnit.SECONDS));
        assertEquals("[8]", second.get(10, TimeUnit.SECONDS));
    }

    @Test
    public void waitsForCapacityAndReusesStoppedContainerCpus() throws Exception {
        used.addAll(cpus(2, 4));
        CountDownLatch waiting = new CountDownLatch(1);
        Future<String> build = executor.submit(() -> CpuAllocator.run(daemon, cpus(2, 4), 2,
                this::snapshot, this::start, waiting::countDown));
        assertTrue(waiting.await(10, TimeUnit.SECONDS));
        assertFalse(build.isDone());
        used.clear();
        assertEquals("[2, 4]", build.get(10, TimeUnit.SECONDS));
    }

    @Test
    public void cancellationWhileWaitingDoesNotReserveCpus() throws Exception {
        used.add(2);
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        Future<?> build = executor.submit(() -> {
            try {
                CpuAllocator.run(daemon, cpus(2), 1, this::snapshot, this::start, waiting::countDown);
                fail("Expected interruption");
            } catch (InterruptedException expected) {
                cancelled.countDown();
            }
            return null;
        });
        assertTrue(waiting.await(10, TimeUnit.SECONDS));
        assertTrue(build.cancel(true));
        assertTrue(cancelled.await(10, TimeUnit.SECONDS));
        used.clear();
        assertEquals("[2]", CpuAllocator.run(daemon, cpus(2), 1, this::snapshot, this::start,
                () -> fail("Cancelled waiter must not retain a reservation")));
    }

    @Test
    public void failedStartRetainsOnlyRecordedContainerCpus() throws Exception {
        try {
            CpuAllocator.run(daemon, cpus(2, 4), 1, this::snapshot, selected -> {
                used.addAll(selected);
                throw new IOException("Container created but start failed");
            }, () -> fail("First build should fit"));
            fail("Expected launch failure");
        } catch (IOException expected) {
            assertEquals("Container created but start failed", expected.getMessage());
        }
        assertEquals("[4]", CpuAllocator.run(daemon, cpus(2, 4), 1, this::snapshot, this::start, () -> fail("CPU 4 is free")));
    }

    @Test
    public void failedStartWithoutContainerAllowsRetry() throws Exception {
        try {
            CpuAllocator.run(daemon, cpus(2), 1, this::snapshot, selected -> {
                throw new IOException("Launch failed");
            }, () -> fail("First build should fit"));
            fail("Expected launch failure");
        } catch (IOException expected) {
            assertEquals("Launch failed", expected.getMessage());
        }
        assertTrue(used.isEmpty());
        assertEquals("[2]", CpuAllocator.run(daemon, cpus(2), 1, this::snapshot, this::start, () -> fail("CPU 2 is free")));
    }

    @Test
    public void failedInspectionDoesNotStartContainer() throws Exception {
        try {
            CpuAllocator.run(daemon, cpus(2), 1, () -> {
                throw new IOException("Inspection failed");
            }, selected -> {
                fail("Cannot allocate from an unknown snapshot");
                return "";
            }, () -> fail("Inspection failure must propagate"));
            fail("Expected inspection failure");
        } catch (IOException expected) {
            assertEquals("Inspection failed", expected.getMessage());
        }
    }

    @Test
    public void oversizedRequestFailsWithoutWaiting() throws Exception {
        try {
            CpuAllocator.run(daemon, cpus(2, 4), 3, this::snapshot, this::start,
                    () -> fail("Request can never fit"));
            fail("Expected oversized request failure");
        } catch (IOException expected) {
            assertEquals("Requested 3 CPUs, but Docker has 2 eligible CPUs", expected.getMessage());
        }
        assertTrue(used.isEmpty());
    }

    @Test
    public void differentDaemonsCanStartIndependently() throws Exception {
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<String> first = executor.submit(() -> CpuAllocator.run(daemon, cpus(2), 1,
                Collections::emptySet, selected -> {
                    starting.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    return "first";
                }, () -> fail("CPU is free")));
        assertTrue(starting.await(10, TimeUnit.SECONDS));
        try {
            Future<String> second = executor.submit(() -> CpuAllocator.run(daemon + "-other", cpus(2), 1,
                    Collections::emptySet, selected -> "second", () -> fail("CPU is free")));
            assertEquals("second", second.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        assertEquals("first", first.get(10, TimeUnit.SECONDS));
    }

    @Test
    public void interruptedStartAllowsRetry() throws Exception {
        try {
            CpuAllocator.run(daemon, cpus(2), 1, this::snapshot, selected -> {
                throw new InterruptedException("Launch interrupted");
            }, () -> fail("CPU is free"));
            fail("Expected interruption");
        } catch (InterruptedException expected) {
            assertEquals("Launch interrupted", expected.getMessage());
        }
        assertEquals("[2]", CpuAllocator.run(daemon, cpus(2), 1, this::snapshot, this::start, () -> fail("CPU 2 is free")));
    }

    private Set<Integer> snapshot() {
        synchronized (used) {
            return new TreeSet<>(used);
        }
    }

    private String start(SortedSet<Integer> selected) {
        assertTrue(Collections.disjoint(used, selected));
        used.addAll(selected);
        return selected.toString();
    }

    private static SortedSet<Integer> cpus(Integer... ids) {
        SortedSet<Integer> result = new TreeSet<>();
        Collections.addAll(result, ids);
        return result;
    }
}
