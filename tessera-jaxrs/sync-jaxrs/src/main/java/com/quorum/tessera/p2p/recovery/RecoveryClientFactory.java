package com.quorum.tessera.p2p.recovery;

import com.quorum.tessera.config.CommunicationType;
import com.quorum.tessera.config.Config;

import java.util.ServiceLoader;

public interface RecoveryClientFactory {

    RecoveryClient create(Config config);

    CommunicationType communicationType();

    static RecoveryClientFactory newFactory(Config config) {
        // TODO: return the stream and let the caller deal with it
        return ServiceLoader.load(RecoveryClientFactory.class)
            .stream().map(ServiceLoader.Provider::get)
                .filter(c -> c.communicationType() == config.getP2PServerConfig().getCommunicationType())
                .findFirst()
                .get();
    }
}
