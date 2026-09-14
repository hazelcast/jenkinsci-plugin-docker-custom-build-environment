# Docker build environment Jenkins CI plugin

This repository+branch contains Hazelcast tweaks (patches) for Docker plugin originally developed by CloudBees:

https://github.com/jenkinsci/docker-custom-build-environment-plugin

The plugin allows to run Jenkins jobs in docker containers.
It also allows defining build environment as a Dockerfile, stored in project SCM.

See also https://wiki.jenkins.io/display/JENKINS/Docker+Custom+Build+Environment+Plugin

## CPU allocation

A positive CPU limit assigns that many logical CPU IDs and retains the Docker CPU quota.
The plugin creates an unstarted container, then serialises CPU selection, configuration and
startup per Docker daemon within one Jenkins controller. It respects existing container CPU
sets, including containers being removed. When there is insufficient capacity, the build waits
on its executor until CPUs become available or it is aborted. Requests larger than the eligible
CPU set fail.

Container labels identify the owning Jenkins instance and build. Failed startup triggers removal
of that container; later allocations also retry cleanup of containers whose owning build has
finished. Until removal succeeds, its CPU set remains reserved. Other available CPUs can still
be allocated, and no Jenkins restart is required. This state lives in Docker, so it survives loss
of the controller process. Containers with unknown owners are retained for manual inspection.

Existing pinned containers are respected, but starts from another controller or an external tool
are not coordinated. Unrestricted containers and host processes can still use the same CPUs,
and distinct logical CPUs may share a physical core. Quiesce old plugin launches during rollout.
CPU configuration uses Docker update, requiring Linux containers and Docker API 1.29 or newer.
