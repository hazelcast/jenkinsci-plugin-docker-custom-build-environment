package com.cloudbees.jenkins.plugins.docker_build_env;

import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Uses only containers carrying this test's unique ownership label. */
public class CpuContainerDockerTest {
    private final String controller = "cpu-test-" + UUID.randomUUID();
    private final Set<String> active = new HashSet<>(Arrays.asList("first", "second"));
    private final List<String> operations = java.util.Collections.synchronizedList(new ArrayList<>());
    private SortedSet<Integer> pool;
    private String loseReply;
    private String interruptReply;
    private boolean failRemoval;
    private ArgumentListBuilder delayed;
    private String delayOperation;

    @Before
    public void selectTwoAvailableCpus() throws Exception {
        CpuContainer allocation = allocation();
        SortedSet<Integer> free = allocation.availableCpus();
        free.removeAll(allocation.usedCpus());
        assertTrue("Docker tests need two unused logical CPUs", free.size() >= 2);
        pool = new TreeSet<>();
        for (Integer cpu : free) {
            pool.add(cpu);
            if (pool.size() == 2) break;
        }
    }

    @After
    public void removeOnlyTestContainers() throws Exception {
        String ids = actual(new ArgumentListBuilder("ps", "--all", "--quiet", "--filter",
                "label=" + CpuContainer.CONTROLLER_LABEL + "=" + controller));
        if (!ids.isEmpty()) {
            actual(new ArgumentListBuilder("rm", "--force").add(ids.split("\\s+")));
        }
    }

    @Test
    public void lostCreateReplyIsReconciledWithoutStartingWork() throws Exception {
        loseReply = "create";
        expectFailure(() -> allocation().start(create("first"), 1));
        String id = testContainer();
        assertEquals("created", inspect(id, "{{.State.Status}}"));
        assertEquals("", inspect(id, "{{.HostConfig.CpusetCpus}}"));
        assertFalse(operations.contains("start"));
        active.remove("first");
        allocation().usedCpus();
        assertEquals("", testContainer());
    }

    @Test
    public void lateCreateAfterCallerFailureCannotStartWork() throws Exception {
        delayOperation = "create";
        expectFailure(() -> allocation().start(create("first"), 1));
        assertEquals("", testContainer());
        String id = actual(delayed);
        assertEquals("created", inspect(id, "{{.State.Status}}"));
        assertEquals("", inspect(id, "{{.HostConfig.CpusetCpus}}"));
        active.remove("first");
        allocation().usedCpus();
        assertEquals("", testContainer());
    }

    @Test
    public void lostStartAndFailedRemovalLeaveOtherCpuUsable() throws Exception {
        loseReply = "start";
        failRemoval = true;
        expectFailure(() -> allocation().start(create("first"), 1));
        String first = testContainer();
        assertEquals("running", inspect(first, "{{.State.Status}}"));
        Set<Integer> firstCpus = CpuAllocator.parse(inspect(first, "{{.HostConfig.CpusetCpus}}"));
        active.remove("first");
        String second = allocation().start(create("second"), 1);
        Set<Integer> secondCpus = CpuAllocator.parse(inspect(second, "{{.HostConfig.CpusetCpus}}"));
        assertTrue(java.util.Collections.disjoint(firstCpus, secondCpus));
        assertEquals("1000000000", inspect(second, "{{.HostConfig.NanoCpus}}"));
        failRemoval = false;
        Set<Integer> used = allocation().usedCpus();
        assertTrue(java.util.Collections.disjoint(firstCpus, used));
        assertTrue(used.containsAll(secondCpus));
        assertFalse(testContainer().contains(first));
    }

    @Test
    public void delayedStartAfterRemovalCannotResurrectContainer() throws Exception {
        delayOperation = "start";
        expectFailure(() -> allocation().start(create("first"), 1));
        assertEquals("", testContainer());
        expectFailure(() -> actual(delayed));
        assertEquals("", testContainer());
    }

    @Test
    public void interruptedStartRemovesContainerAndAllowsNextBuild() throws Exception {
        interruptReply = "start";
        try {
            allocation().start(create("first"), 1);
            fail("Expected interrupted start");
        } catch (InterruptedException expected) {
            assertEquals("Injected interruption after start", expected.getMessage());
        }
        assertEquals("", testContainer());
        String second = allocation().start(create("second"), 1);
        assertEquals("running", inspect(second, "{{.State.Status}}"));
    }

    @Test
    public void lostUpdateReplyNeverStartsContainer() throws Exception {
        loseReply = "update";
        expectFailure(() -> allocation().start(create("first"), 1));
        assertFalse(operations.contains("start"));
        assertEquals("", testContainer());
    }

    @Test
    public void simultaneousStartsReceiveDisjointRealCpuSets() throws Exception {
        java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Future<String> first = threads.submit(() -> {
                start.await();
                return allocation().start(create("first"), 1);
            });
            java.util.concurrent.Future<String> second = threads.submit(() -> {
                start.await();
                return allocation().start(create("second"), 1);
            });
            start.countDown();
            Set<Integer> a = CpuAllocator.parse(inspect(first.get(45, TimeUnit.SECONDS), "{{.HostConfig.CpusetCpus}}"));
            Set<Integer> b = CpuAllocator.parse(inspect(second.get(45, TimeUnit.SECONDS), "{{.HostConfig.CpusetCpus}}"));
            assertEquals(1, a.size());
            assertEquals(1, b.size());
            assertTrue(java.util.Collections.disjoint(a, b));
        } finally {
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private CpuContainer allocation() {
        return new CpuContainer(args -> {
            String operation = args.toList().get(0);
            operations.add(operation);
            if (operation.equals(delayOperation)) {
                delayOperation = null;
                delayed = args.clone();
                throw new IOException("Delayed " + operation + " request");
            }
            if (operation.equals("rm") && failRemoval) throw new IOException("Injected cleanup failure");
            String result = actual(args);
            if (operation.equals(loseReply)) {
                loseReply = null;
                throw new IOException("Lost reply after " + operation);
            }
            if (operation.equals(interruptReply)) {
                interruptReply = null;
                throw new InterruptedException("Injected interruption after " + operation);
            }
            if (operation.equals("run") && pool != null) {
                // Restrict the test pool without changing daemon or unrelated container settings.
                return "Cpus_allowed_list:\t" + pool.stream().map(Object::toString).collect(Collectors.joining(","));
            }
            return result;
        }, controller, active::contains, System.out::println);
    }

    private ArgumentListBuilder create(String build) {
        return new ArgumentListBuilder("create", "--tty", "--label", CpuContainer.CONTROLLER_LABEL + "=" + controller,
                "--label", CpuContainer.BUILD_LABEL + "=" + build, "alpine:3.16", "cat");
    }

    private String testContainer() throws Exception {
        return actual(new ArgumentListBuilder("ps", "--all", "--quiet", "--no-trunc", "--filter",
                "label=" + CpuContainer.CONTROLLER_LABEL + "=" + controller));
    }

    private String inspect(String id, String format) throws Exception {
        return actual(new ArgumentListBuilder("inspect", "--format", format, id));
    }

    static String actual(ArgumentListBuilder args) throws IOException, InterruptedException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int status = new Launcher.LocalLauncher(TaskListener.NULL).launch()
                .cmds(args.clone().prepend("docker")).stdout(output).stderr(output).quiet(true)
                .start().joinWithTimeout(30, TimeUnit.SECONDS, TaskListener.NULL);
        if (status != 0) throw new IOException("Docker command failed: " + output.toString("UTF-8"));
        return output.toString("UTF-8").trim();
    }

    private interface Operation { String run() throws Exception; }

    private static void expectFailure(Operation action) throws Exception {
        try {
            action.run();
            fail("Expected operation failure");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
