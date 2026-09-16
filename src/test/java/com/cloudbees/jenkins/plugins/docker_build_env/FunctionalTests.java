package com.cloudbees.jenkins.plugins.docker_build_env;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
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
import org.jvnet.hudson.test.TestBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;


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

    @Test
    public void test_cpu_quota_only() throws Exception {
        assertQuotaOnlyLimits("1", "1000000000");
    }

    @Test
    public void test_cpu_quota_only_without_limit() throws Exception {
        assertQuotaOnlyLimits(null, "0");
    }

    private void assertQuotaOnlyLimits(String cpus, final String expectedQuota) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        DockerBuildWrapper wrapper = new DockerBuildWrapper(new PullDockerImageSelector("alpine:3.16"),
                "", new DockerServerEndpoint("", ""), "", true, false, Collections.<Volume>emptyList(),
                null, "cat", false, "bridge", null, cpus, false);
        wrapper.setCpuQuotaOnly(true);
        project.getBuildWrappersList().add(wrapper);
        project.getBuildersList().add(new Shell("echo quota-only-build"));
        project.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws IOException, InterruptedException {
                String container = build.getAction(BuiltInContainer.class).container;
                assertEquals(expectedQuota + "|", docker("inspect", "--format",
                        "{{.HostConfig.NanoCpus}}|{{.HostConfig.CpusetCpus}}", container));
                return true;
            }
        });
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("quota-only-build", build);
        String log = FileUtils.readFileToString(build.getLogFile());
        assertFalse(log.contains("Checking 1 CPU limit"));
        assertFalse(log.contains("availableProcessors on the slave machine"));
    }

    private String docker(String... arguments) throws IOException, InterruptedException {
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
