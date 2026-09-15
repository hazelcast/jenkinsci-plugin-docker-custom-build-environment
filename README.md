# Docker build environment Jenkins CI plugin

This repository+branch contains Hazelcast tweaks (patches) for Docker plugin originally developed by CloudBees:

https://github.com/jenkinsci/docker-custom-build-environment-plugin

The plugin allows to run Jenkins jobs in docker containers.
It also allows defining build environment as a Dockerfile, stored in project SCM.

See also https://wiki.jenkins.io/display/JENKINS/Docker+Custom+Build+Environment+Plugin

## CPU limits

A positive CPU value sets Docker's `--cpus` quota. By default, the plugin also
selects a random CPU set when its CPU-count probe indicates that pinning is needed.

Enable **Use CPU quota without pinning** in the job's advanced Docker settings to
keep the quota and let the host OS schedule threads across the container's eligible
CPUs. This skips the CPU-count probes and does not add `--cpuset-cpus`. The option
is off by default, so existing jobs retain their current behaviour. Blank, zero or
negative CPU values leave the container without a CPU limit in either mode.

Before enabling it, check processor counts and parallelism in the actual build and
test processes. Some runtimes and tools detect the host CPU count despite a quota.
Configure their parallelism in the job where needed; for example, a HotSpot JVM
with a quota of 8 can use `-XX:ActiveProcessorCount=8` if automatic detection is wrong.
The plugin does not set JVM options. A quota limits CPU time; it does not reserve
capacity or prevent contention with other workloads.
