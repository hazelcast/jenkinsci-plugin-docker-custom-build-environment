package com.cloudbees.jenkins.plugins.docker_build_env;

import java.io.IOException;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** Coordinates container starts on the same Docker daemon within one Jenkins controller. */
final class CpuAllocator {

    // Keep locks for the controller's lifetime: removing an unlocked lock races with its waiters.
    private static final ConcurrentMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private CpuAllocator() {
    }

    interface CpuUsage {
        Set<Integer> get() throws IOException, InterruptedException;
    }

    interface ContainerStarter {
        String start(SortedSet<Integer> cpus) throws IOException, InterruptedException;
    }

    static String run(String daemon, SortedSet<Integer> available, int count, CpuUsage usage,
                      ContainerStarter starter, Runnable waiting) throws IOException, InterruptedException {
        if (daemon.isEmpty()) {
            throw new IOException("Docker daemon returned an empty ID");
        }
        if (count < 1 || count > available.size()) {
            throw new IOException("Requested " + count + " CPUs, but Docker has " + available.size() + " eligible CPUs");
        }
        ReentrantLock lock = LOCKS.computeIfAbsent(daemon, id -> new ReentrantLock());
        boolean reportedWaiting = false;
        while (true) {
            lock.lockInterruptibly();
            try {
                SortedSet<Integer> free = new TreeSet<>(available);
                free.removeAll(usage.get());
                if (free.size() >= count) {
                    SortedSet<Integer> selected = new TreeSet<>();
                    for (Integer cpu : free) {
                        selected.add(cpu);
                        if (selected.size() == count) {
                            break;
                        }
                    }
                    // Docker must record the cpuset before another build can inspect the daemon.
                    return starter.start(selected);
                }
            } finally {
                lock.unlock();
            }
            if (!reportedWaiting) {
                waiting.run();
                reportedWaiting = true;
            }
            Thread.sleep(5000);
        }
    }

    static SortedSet<Integer> parse(String value) throws IOException {
        SortedSet<Integer> result = new TreeSet<>();
        if (value.trim().isEmpty()) {
            return result;
        }
        for (String part : value.trim().split(",", -1)) {
            String range = part.trim();
            if (!range.matches("[0-9]+(-[0-9]+)?")) {
                throw new IOException("Invalid Docker CPU set: " + value);
            }
            String[] ends = range.split("-");
            try {
                int first = Integer.parseInt(ends[0]);
                int last = Integer.parseInt(ends[ends.length - 1]);
                if (first > last) {
                    throw new IOException("Invalid Docker CPU range: " + range);
                }
                for (int cpu = first; ; cpu++) {
                    result.add(cpu);
                    if (cpu == last) {
                        break;
                    }
                }
            } catch (NumberFormatException e) {
                throw new IOException("Invalid Docker CPU set: " + value, e);
            }
        }
        return result;
    }
}
