package com.cloudbees.jenkins.plugins.docker_build_env;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

import org.apache.commons.io.FileUtils;

import jenkins.security.MasterToSlaveCallable;
import net.sf.json.JSONObject;

public class WorkaroundDockerCfg extends MasterToSlaveCallable<Void, IOException> {

    private static final long serialVersionUID = 1L;

    @Override
    public Void call() throws IOException {
        File configDir = new File(System.getProperty("user.home"), ".docker");
        File config = new File(configDir, "config.json");
        if (!config.exists()) {
            configDir.mkdirs();
            JSONObject json = new JSONObject();
            json.element("auths", Collections.emptyMap());
            try {
                FileUtils.writeStringToFile(config, json.toString(2), "UTF-8");
                System.out.println("Created 'empty' Docker config file: " + config);
            } catch (IOException e) {
                e.printStackTrace();
                System.out.println("Failed to create Docker config file: " + config + ", msg: " + e.getMessage());
            }
        }
        return null;
    }

}
