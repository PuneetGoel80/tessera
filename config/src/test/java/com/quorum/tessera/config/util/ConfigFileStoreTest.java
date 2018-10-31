package com.quorum.tessera.config.util;

import com.quorum.tessera.config.Config;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.Before;
import org.junit.Test;

public class ConfigFileStoreTest {

    private ConfigFileStore configFileStore;

    private Path path;

    @Before
    public void onSetUp() throws Exception {
        path = Files.createTempFile(UUID.randomUUID().toString(), ".junit");
        path.toFile().deleteOnExit();

        final URL sampleConfig = getClass().getResource("/sample.json");
        try (InputStream in = sampleConfig.openStream()) {
            Config initialConfig = JaxbUtil.unmarshal(in, Config.class);
            JaxbUtil.marshal(initialConfig, Files.newOutputStream(path));
        }

        configFileStore = ConfigFileStore.create(path);
    }

    @Test
    public void getReturnsSameInstance() {
        assertThat(ConfigFileStore.get()).isSameAs(configFileStore);

    }

}
