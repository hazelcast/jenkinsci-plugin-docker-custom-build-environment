package com.cloudbees.jenkins.plugins.docker_build_env;

import hudson.util.ArgumentListBuilder;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.io.IOException;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Docker state is the allocation record, including after loss of the controller process. */
final class CpuContainer {
    static final String CONTROLLER_LABEL = "com.hazelcast.jenkins.cpu.controller";
    static final String BUILD_LABEL = "com.hazelcast.jenkins.cpu.build";

    interface Command {
        String run(ArgumentListBuilder args) throws IOException, InterruptedException;
    }

    private final Command docker;
    private final String controller;
    private final Predicate<String> activeBuild;
    private final Consumer<String> log;

    CpuContainer(Command docker, String controller, Predicate<String> activeBuild, Consumer<String> log) {
        this.docker = docker;
        this.controller = controller;
        this.activeBuild = activeBuild;
        this.log = log;
    }

    String start(ArgumentListBuilder create, int count) throws IOException, InterruptedException {
        String daemon = run("info", "--format", "{{.ID}}");
        SortedSet<Integer> available = availableCpus();
        if (count < 1 || count > available.size()) {
            throw new IOException("Requested " + count + " CPUs, but Docker has " + available.size() + " eligible CPUs");
        }
        // A late create after a lost response cannot start a workload or claim CPUs.
        String id = docker.run(create);
        if (!id.matches("[a-f0-9]{64}")) {
            throw new IOException("Docker create did not return a container ID");
        }
        try {
            return CpuAllocator.run(daemon, available, count, this::usedCpus, selected -> {
                String cpus = selected.stream().map(Object::toString).collect(Collectors.joining(","));
                log.accept("Assigning available CPUs (--cpuset-cpus=) " + cpus);
                // Never issue start until Docker has confirmed that the CPU set is recorded.
                run("update", "--cpus", Integer.toString(count), "--cpuset-cpus", cpus, id);
                run("start", id);
                return id;
            }, () -> log.accept("Waiting for " + count + " available Docker CPUs"));
        } catch (IOException | InterruptedException | RuntimeException e) {
            boolean interrupted = Thread.interrupted();
            try {
                remove(id);
            } catch (IOException | RuntimeException cleanup) {
                e.addSuppressed(cleanup);
                log.accept("Could not remove Docker container " + id + "; cleanup will be retried after this build finishes");
            } catch (InterruptedException cleanup) {
                cleanup.addSuppressed(e);
                throw cleanup;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            throw e;
        }
    }

    SortedSet<Integer> availableCpus() throws IOException, InterruptedException {
        String status = run("run", "--rm", "--entrypoint", "/bin/cat", "alpine:3.16", "/proc/self/status");
        for (String line : status.split("\\r?\\n")) {
            if (line.startsWith("Cpus_allowed_list:")) {
                SortedSet<Integer> cpus = CpuAllocator.parse(line.substring("Cpus_allowed_list:".length()));
                if (!cpus.isEmpty()) {
                    return cpus;
                }
            }
        }
        throw new IOException("Could not determine eligible CPUs on the Docker daemon");
    }

    Set<Integer> usedCpus() throws IOException, InterruptedException {
        String ids = run("ps", "--all", "--quiet", "--no-trunc");
        while (!ids.isEmpty()) {
            JSONArray containers;
            try {
                containers = JSONArray.fromObject(docker.run(new ArgumentListBuilder("inspect", "--type", "container")
                        .add(ids.split("\\s+"))));
            } catch (IOException e) {
                String current = run("ps", "--all", "--quiet", "--no-trunc");
                if (current.equals(ids)) {
                    throw e;
                }
                ids = current; // A container may disappear between listing and inspection.
                continue;
            }
            SortedSet<Integer> used = new TreeSet<>();
            for (Object entry : containers) {
                JSONObject container = (JSONObject) entry;
                JSONObject labels = container.getJSONObject("Config").optJSONObject("Labels");
                boolean owned = labels != null && controller.equals(labels.optString(CONTROLLER_LABEL));
                String owner = labels == null ? "" : labels.optString(BUILD_LABEL);
                if (owned && !owner.isEmpty() && !activeBuild.test(owner)) {
                    try {
                        remove(container.getString("Id"));
                        continue;
                    } catch (IOException e) {
                        // Retain this container's CPUs only. Retry on the next allocation attempt.
                        log.accept("Could not clean up abandoned Docker container " + container.getString("Id"));
                    }
                }
                String state = container.getJSONObject("State").getString("Status");
                if (owned || !("exited".equals(state) || "dead".equals(state))) {
                    used.addAll(CpuAllocator.parse(container.getJSONObject("HostConfig").getString("CpusetCpus")));
                }
            }
            return used;
        }
        return new TreeSet<>();
    }

    private void remove(String id) throws IOException, InterruptedException {
        // A successful removal fences any delayed start targeting this immutable ID.
        run("rm", "--force", id);
    }

    private String run(String... args) throws IOException, InterruptedException {
        return docker.run(new ArgumentListBuilder(args));
    }
}
