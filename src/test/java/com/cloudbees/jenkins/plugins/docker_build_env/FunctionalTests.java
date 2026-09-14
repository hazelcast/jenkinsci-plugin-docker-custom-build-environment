package com.cloudbees.jenkins.plugins.docker_build_env;

import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.model.queue.QueueTaskFuture;
import hudson.util.ArgumentListBuilder;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.tasks.Shell;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.docker.commons.credentials.DockerServerEndpoint;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SingleFileSCM;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;


/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class FunctionalTests {

    @Rule  // @ClassRule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void run_inside_pulled_container() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();

        project.getBuildWrappersList().add(
            new DockerBuildWrapper(
                new PullDockerImageSelector("ubuntu:14.04"),
                "", new DockerServerEndpoint("", ""), "", true, false, Collections.<Volume>emptyList(), null, "cat", false, "bridge", null, null, false)
        );
        project.getBuildersList().add(new Shell("lsb_release  -a"));

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.SUCCESS, build);
        String s = FileUtils.readFileToString(build.getLogFile());
        assertThat(s, containsString("Ubuntu 14.04"));
        jenkins.buildAndAssertSuccess(project);
    }

    @Test
    public void test_cpus() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();

        project.getBuildWrappersList().add(
            new DockerBuildWrapper(
                new PullDockerImageSelector("alpine:3.16"),
                "", new DockerServerEndpoint("", ""), "", true, false, Collections.<Volume>emptyList(), null, "cat", false, "bridge", null, "1", false)
        );
        project.getBuildersList().add(new Shell("echo \"nproc==$(nproc)xxx\""));

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.SUCCESS, build);
        String s = FileUtils.readFileToString(build.getLogFile());
        assertThat(s, containsString("nproc==1xxx"));
        jenkins.buildAndAssertSuccess(project);
    }

    @Test(timeout = 120000)
    public void test_cpu_capacity_wait_and_abort() throws Exception {
        jenkins.jenkins.setNumExecutors(2);
        String status = docker("run", "--rm", "--entrypoint", "/bin/cat", "alpine:3.16", "/proc/self/status");
        SortedSet<Integer> available = new TreeSet<>();
        for (String line : status.split("\\r?\\n")) {
            if (line.startsWith("Cpus_allowed_list:")) {
                available = CpuAllocator.parse(line.substring("Cpus_allowed_list:".length()));
            }
        }
        assertFalse("Docker must report its eligible CPU set", available.isEmpty());
        String count = Integer.toString(available.size());
        FreeStyleProject holder = cpuProject(count, "echo holding-cpus; while [ ! -f release ]; do sleep 1; done");
        FreeStyleProject waiter = cpuProject(count, "echo acquired-cpus");
        waiter.setAssignedNode(jenkins.createOnlineSlave());
        QueueTaskFuture<FreeStyleBuild> holding = holder.scheduleBuild2(0);
        QueueTaskFuture<FreeStyleBuild> waiting = null;
        try {
            FreeStyleBuild first = holding.waitForStart();
            jenkins.waitForMessage("holding-cpus", first);
            String container = first.getAction(BuiltInContainer.class).container;
            assertEquals(available, CpuAllocator.parse(docker("inspect", "--format",
                    "{{.HostConfig.CpusetCpus}}", container)));
            waiting = waiter.scheduleBuild2(0);
            FreeStyleBuild second = waiting.waitForStart();
            jenkins.waitForMessage("Waiting for " + count + " available Docker CPUs", second);
            assertNull("Waiting build must not have a container", second.getAction(BuiltInContainer.class).container);
            assertNotNull(second.getExecutor());
            second.getExecutor().interrupt();
            jenkins.assertBuildStatus(Result.ABORTED, waiting.get(30, TimeUnit.SECONDS));
            first.getWorkspace().child("release").write("release", "UTF-8");
            jenkins.assertBuildStatus(Result.SUCCESS, holding.get(30, TimeUnit.SECONDS));
            // The stopped container releases its CPUs for a subsequent build.
            jenkins.assertBuildStatus(Result.SUCCESS, waiter.scheduleBuild2(0).get(30, TimeUnit.SECONDS));
        } finally {
            if (waiting != null) {
                waiting.cancel(true);
            }
            holding.cancel(true);
        }
    }

    @Test
    public void test_without_cpu_limit() throws Exception {
        jenkins.buildAndAssertSuccess(cpuProject(null, "echo unlimited-build"));
    }

    private FreeStyleProject cpuProject(String cpus, String script) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildWrappersList().add(new DockerBuildWrapper(new PullDockerImageSelector("alpine:3.16"),
                "", new DockerServerEndpoint("", ""), "", true, false, Collections.<Volume>emptyList(),
                null, "cat", false, "bridge", null, cpus, false));
        project.getBuildersList().add(new Shell(script));
        return project;
    }

    private String docker(String... arguments) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ArgumentListBuilder command = new ArgumentListBuilder("docker").add(arguments);
        int status = new Launcher.LocalLauncher(TaskListener.NULL).launch().cmds(command)
                .stdout(output).stderr(System.err).join();
        assertEquals("Docker command failed: " + command, 0, status);
        return output.toString("UTF-8").trim();
    }

    @Test
    public void run_inside_built_container() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.setScm(new SingleFileSCM("Dockerfile", "FROM ubuntu:14.04"));

        project.getBuildWrappersList().add(
                new DockerBuildWrapper(
                        new DockerfileImageSelector(".", "Dockerfile"),
                        "", new DockerServerEndpoint("", ""), "", true, false, Collections.<Volume>emptyList(), null, "cat", false, "bridge", null, null, true)
        );
        project.getBuildersList().add(new Shell("lsb_release  -a"));

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.SUCCESS, build);
        String s = FileUtils.readFileToString(build.getLogFile());
        assertThat(s, containsString("Ubuntu 14.04"));
        jenkins.buildAndAssertSuccess(project);
    }

    @Test
    public void run_inside_built_python_pip_container() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        String dockerfile = String.join(
                                        "\n",
                                        "FROM python:3.6.4-alpine3.4",
//                                        "RUN echo Successfully built THIS_STRING_SHOULD_NOT_BE_CAPTURED_AS_IMAGE_ID",
//                                        "RUN echo writing image THIS_STRING_SHOULD_NOT_BE_CAPTURED_AS_IMAGE_ID done",
                                        "RUN pip install simplejson==3.13.2"
        );
        project.setScm(new SingleFileSCM("Dockerfile", dockerfile));

        project.getBuildWrappersList().add(
                new DockerBuildWrapper(
                        new DockerfileImageSelector(".", "Dockerfile"),
                        "", new DockerServerEndpoint("", ""), "", true, false, Collections.<Volume>emptyList(), null, "cat", false, "bridge", null, null, true)
        );
        project.getBuildersList().add(new Shell("python -V"));

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.SUCCESS, build);
        String s = FileUtils.readFileToString(build.getLogFile());
        assertThat(s, containsString("Python 3.6.4"));
        jenkins.buildAndAssertSuccess(project);
    }

}
