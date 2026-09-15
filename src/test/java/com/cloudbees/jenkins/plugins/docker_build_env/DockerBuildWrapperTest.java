package com.cloudbees.jenkins.plugins.docker_build_env;

import hudson.model.FreeStyleProject;
import org.jenkinsci.plugins.docker.commons.credentials.DockerServerEndpoint;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DockerBuildWrapperTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void cpu_quota_only_configuration_roundtrip() throws Exception {
        FreeStyleProject project = projectWithCpuLimit();
        project.getBuildWrappersList().get(DockerBuildWrapper.class).setCpuQuotaOnly(true);
        project = jenkins.configRoundtrip(project);
        assertTrue(project.getBuildWrappersList().get(DockerBuildWrapper.class).isCpuQuotaOnly());
        assertEquals("1", project.getBuildWrappersList().get(DockerBuildWrapper.class).getCpu());

        project.getBuildWrappersList().get(DockerBuildWrapper.class).setCpuQuotaOnly(false);
        project = jenkins.configRoundtrip(project);
        assertFalse(project.getBuildWrappersList().get(DockerBuildWrapper.class).isCpuQuotaOnly());
    }

    @Test
    public void cpu_quota_only_defaults_off_for_existing_jobs() throws Exception {
        FreeStyleProject project = projectWithCpuLimit();
        project.save();
        String legacyConfig = project.getConfigFile().asString()
                .replace("<cpuQuotaOnly>false</cpuQuotaOnly>", "");
        assertFalse(legacyConfig.contains("cpuQuotaOnly"));

        project.updateByXml(new StreamSource(new StringReader(legacyConfig)));

        DockerBuildWrapper wrapper = project.getBuildWrappersList().get(DockerBuildWrapper.class);
        assertFalse(wrapper.isCpuQuotaOnly());
        assertEquals("1", wrapper.getCpu());
    }

    private FreeStyleProject projectWithCpuLimit() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildWrappersList().add(new DockerBuildWrapper(new PullDockerImageSelector("alpine:3.16"),
                "", new DockerServerEndpoint("", ""), "", true, false, Collections.<Volume>emptyList(),
                null, "cat", false, "bridge", null, "1", false));
        return project;
    }
}
