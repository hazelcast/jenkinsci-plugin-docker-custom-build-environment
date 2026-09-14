package com.cloudbees.jenkins.plugins.docker_build_env;

import hudson.EnvVars;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.docker.commons.credentials.DockerServerEndpoint;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.RealJenkinsRule;
import org.jvnet.hudson.test.TestBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class CpuRecoveryJenkinsTest {
    @Rule public RealJenkinsRule jenkins = new RealJenkinsRule().withTimeout(180);

    @Test
    public void recoversAbandonedContainerInANewControllerProcess() throws Throwable {
        File[] savedHome = new File[1];
        try {
            jenkins.then(r -> {
                FreeStyleProject project = r.createFreeStyleProject("before-restart");
                project.getBuildersList().add(new ContainerBuilder(true));
                r.buildAndAssertSuccess(project);
                // TestBuilder is only available during a remote step, not while Jenkins reloads jobs.
                project.getBuildersList().clear();
                project.save();
                Files.write(new File(r.jenkins.getRootDir(), "controller-process").toPath(),
                        ManagementFactory.getRuntimeMXBean().getName().getBytes(StandardCharsets.UTF_8));
            });
            savedHome[0] = jenkins.getHome();
            jenkins.then(r -> {
                String oldProcess = new String(Files.readAllBytes(new File(r.jenkins.getRootDir(), "controller-process").toPath()),
                        StandardCharsets.UTF_8);
                assertNotEquals(oldProcess, ManagementFactory.getRuntimeMXBean().getName());
                String id = new String(Files.readAllBytes(new File(r.jenkins.getRootDir(), "abandoned-container").toPath()),
                        StandardCharsets.UTF_8);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                assertEquals(0, new Launcher.LocalLauncher(r.createTaskListener()).launch()
                        .cmds("docker", "inspect", "--format", "{{.State.Status}}", id).stdout(output).quiet(true).join());
                assertEquals("running", output.toString("UTF-8").trim());
                FreeStyleProject project = r.createFreeStyleProject("after-restart");
                project.getBuildersList().add(new ContainerBuilder(false));
                r.buildAndAssertSuccess(project);
                assertNotEquals(0, new Launcher.LocalLauncher(r.createTaskListener()).launch()
                        .cmds("docker", "inspect", id).stdout(new ByteArrayOutputStream())
                        .stderr(new ByteArrayOutputStream()).quiet(true).join());
            });
        } finally {
            File dir = savedHome[0] == null ? jenkins.getHome() : savedHome[0];
            File idFile = new File(dir, "abandoned-container");
            if (idFile.isFile()) {
                String id = new String(Files.readAllBytes(idFile.toPath()), StandardCharsets.UTF_8);
                // Only the immutable ID recorded by this test can be removed here.
                if (id.matches("[a-f0-9]{64}")) {
                    new Launcher.LocalLauncher(hudson.model.TaskListener.NULL).launch()
                            .cmds("docker", "rm", "--force", id).stdout(new ByteArrayOutputStream())
                            .stderr(new ByteArrayOutputStream()).quiet(true).join();
                }
            }
        }
    }

    public static final class ContainerBuilder extends TestBuilder {
        private final boolean abandon;

        public ContainerBuilder(boolean abandon) {
            this.abandon = abandon;
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws IOException, InterruptedException {
            try (Docker docker = new Docker(new DockerServerEndpoint("", ""), "", "", build,
                    launcher, listener, true, false)) {
                docker.setupCredentials(build);
                String id = docker.runDetached("alpine:3.16", "/", Collections.emptyMap(), Collections.emptyMap(),
                        Collections.emptyMap(), new EnvVars(), Collections.emptySet(), "host", null, "1", "cat");
                if (abandon) {
                    Files.write(new File(Jenkins.get().getRootDir(), "abandoned-container").toPath(),
                            id.getBytes(StandardCharsets.UTF_8));
                } else {
                    docker.kill(id);
                }
            }
            return true;
        }
    }
}
