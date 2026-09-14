package com.cloudbees.jenkins.plugins.docker_build_env;

import hudson.util.ArgumentListBuilder;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CpuContainerTest {
    private final FakeDocker docker = new FakeDocker();
    private final Set<String> active = new HashSet<>(Arrays.asList("first", "second"));

    @Test
    public void createsWithoutCpusThenConfiguresBeforeStarting() throws Exception {
        String id = allocation().start(create("first"), 1);
        assertEquals("2", docker.cpus(id));
        assertEquals("running", docker.state(id));
        assertTrue(docker.operations.indexOf("create") < docker.operations.indexOf("update"));
        assertTrue(docker.operations.indexOf("update") < docker.operations.indexOf("start"));
    }

    @Test
    public void lostCreateReplyLeavesNoRunningWorkloadAndIsReconciled() throws Exception {
        docker.loseReply = "create";
        expectFailure(() -> allocation().start(create("first"), 1));
        String id = docker.containers.keySet().iterator().next();
        assertEquals("", docker.cpus(id));
        assertEquals("created", docker.state(id));
        assertFalse(docker.operations.contains("start"));
        active.remove("first");
        assertTrue(allocation().usedCpus().isEmpty());
        assertTrue(docker.containers.isEmpty());
    }

    @Test
    public void failedCpuUpdateNeverStartsTheContainer() throws Exception {
        docker.loseReply = "update";
        expectFailure(() -> allocation().start(create("first"), 1));
        assertFalse(docker.operations.contains("start"));
        assertTrue(docker.containers.isEmpty());
    }

    @Test
    public void lostStartReplyRemovesTheRunningContainer() throws Exception {
        docker.loseReply = "start";
        expectFailure(() -> allocation().start(create("first"), 1));
        assertTrue(docker.operations.contains("start"));
        assertTrue(docker.containers.isEmpty());
        assertEquals("2", docker.cpus(allocation().start(create("second"), 1)));
    }

    @Test
    public void failedRemovalRetainsOnlyAffectedCpusAndRecoversWithoutRestart() throws Exception {
        docker.loseReply = "start";
        docker.failRemoval = true;
        expectFailure(() -> allocation().start(create("first"), 1));
        active.remove("first");
        String next = allocation().start(create("second"), 1);
        assertEquals("4", docker.cpus(next));
        docker.failRemoval = false;
        assertEquals(new HashSet<>(Arrays.asList(4)), allocation().usedCpus());
        assertEquals(1, docker.containers.size());
    }

    @Test
    public void newAllocatorReconcilesDockerStateAndPreservesForeignContainers() throws Exception {
        String abandoned = allocation().start(create("first"), 1);
        String foreign = docker.run(create("other").add("unused"));
        docker.containers.get(foreign).getJSONObject("Config").getJSONObject("Labels")
                .put(CpuContainer.CONTROLLER_LABEL, "another-controller");
        docker.containers.get(foreign).getJSONObject("HostConfig").put("CpusetCpus", "8");
        active.remove("first");
        CpuContainer fresh = allocation();
        assertEquals(new HashSet<>(Arrays.asList(8)), fresh.usedCpus());
        assertFalse(docker.containers.containsKey(abandoned));
        assertTrue(docker.containers.containsKey(foreign));
    }

    @Test
    public void liveBuildContainerIsNeverReconciledAway() throws Exception {
        String id = allocation().start(create("first"), 1);
        assertEquals(new HashSet<>(Arrays.asList(2)), allocation().usedCpus());
        assertTrue(docker.containers.containsKey(id));
        assertFalse(docker.operations.contains("rm"));
    }

    @Test
    public void lateStartCannotRunAfterConfirmedRemoval() throws Exception {
        List<String> delayed = new ArrayList<>();
        CpuContainer allocation = new CpuContainer(args -> {
            if (args.toList().get(0).equals("start")) {
                delayed.addAll(args.toList());
                throw new IOException("Start sent, response lost");
            }
            return docker.run(args);
        }, "controller", active::contains, message -> { });
        expectFailure(() -> allocation.start(create("first"), 1));
        expectFailure(() -> docker.run(new ArgumentListBuilder(delayed.toArray(new String[0]))));
        assertTrue(docker.containers.isEmpty());
    }

    @Test
    public void interruptionDuringCleanupIsNotHiddenByTheOriginalFailure() throws Exception {
        docker.loseReply = "start";
        CpuContainer allocation = new CpuContainer(args -> {
            if (args.toList().get(0).equals("rm")) throw new InterruptedException("Cleanup aborted");
            return docker.run(args);
        }, "controller", active::contains, message -> { });
        try {
            allocation.start(create("first"), 1);
            fail("Expected cancellation during cleanup");
        } catch (InterruptedException expected) {
            assertEquals("Cleanup aborted", expected.getMessage());
            assertEquals(1, expected.getSuppressed().length);
        }
        assertEquals(1, docker.containers.size());
    }

    private CpuContainer allocation() {
        return new CpuContainer(docker, "controller", active::contains, message -> { });
    }

    private static ArgumentListBuilder create(String owner) {
        return new ArgumentListBuilder("create", "--label", CpuContainer.CONTROLLER_LABEL + "=controller",
                "--label", CpuContainer.BUILD_LABEL + "=" + owner, "alpine:3.16", "cat");
    }

    interface Operation {
        String run() throws Exception;
    }

    private static void expectFailure(Operation operation) throws Exception {
        try {
            operation.run();
            fail("Expected an injected operation failure");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static final class FakeDocker implements CpuContainer.Command {
        final Map<String, JSONObject> containers = new LinkedHashMap<>();
        final List<String> operations = new ArrayList<>();
        final String daemon = UUID.randomUUID().toString();
        String loseReply;
        boolean failRemoval;
        int sequence;

        @Override
        public String run(ArgumentListBuilder command) throws IOException {
            List<String> args = command.toList();
            String op = args.get(0);
            operations.add(op);
            String result;
            switch (op) {
                case "info": result = daemon; break;
                case "run": result = "Cpus_allowed_list:\t2,4,8"; break;
                case "ps": result = String.join("\n", containers.keySet()); break;
                case "inspect":
                    JSONArray inspected = new JSONArray();
                    for (String id : args.subList(3, args.size())) {
                        inspected.add(container(id));
                    }
                    result = inspected.toString();
                    break;
                case "create":
                    assertFalse(args.contains("--cpuset-cpus"));
                    String id = String.format("%064x", ++sequence);
                    JSONObject labels = new JSONObject();
                    for (int i = 1; i < args.size(); i++) {
                        if (args.get(i).equals("--label")) {
                            String[] label = args.get(++i).split("=", 2);
                            labels.put(label[0], label[1]);
                        }
                    }
                    JSONObject created = new JSONObject().element("Id", id)
                            .element("Config", new JSONObject().element("Labels", labels))
                            .element("HostConfig", new JSONObject().element("CpusetCpus", ""))
                            .element("State", new JSONObject().element("Status", "created"));
                    containers.put(id, created);
                    result = id;
                    break;
                case "update":
                    String updated = args.get(args.size() - 1);
                    container(updated).getJSONObject("HostConfig").put("CpusetCpus", args.get(args.indexOf("--cpuset-cpus") + 1));
                    result = updated;
                    break;
                case "start":
                    result = args.get(1);
                    container(result).getJSONObject("State").put("Status", "running");
                    break;
                case "rm":
                    if (failRemoval) throw new IOException("Removal unavailable");
                    result = args.get(2);
                    container(result);
                    containers.remove(result);
                    break;
                default: throw new AssertionError("Unexpected command " + args);
            }
            if (op.equals(loseReply)) {
                loseReply = null;
                throw new IOException("Lost " + op + " reply after operation completed");
            }
            return result;
        }

        private JSONObject container(String id) throws IOException {
            JSONObject result = containers.get(id);
            if (result == null) throw new IOException("No such container: " + id);
            return result;
        }

        String cpus(String id) throws IOException {
            return container(id).getJSONObject("HostConfig").getString("CpusetCpus");
        }

        String state(String id) throws IOException {
            return container(id).getJSONObject("State").getString("Status");
        }
    }
}
