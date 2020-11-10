package com.quorum.tessera.q2t;

import com.quorum.tessera.api.common.RawTransactionResource;
import com.quorum.tessera.app.TesseraRestApplication;
import com.quorum.tessera.config.AppType;
import com.quorum.tessera.transaction.EncodedPayloadManager;
import com.quorum.tessera.transaction.TransactionManager;
import com.quorum.tessera.transaction.TransactionManagerFactory;
import io.swagger.annotations.Api;

import javax.ws.rs.ApplicationPath;
import java.util.Set;

/**
 * The main application that is submitted to the HTTP server Contains all the service classes created by the service
 * locator
 */
@Api
@ApplicationPath("/")
public class Q2TRestApp extends TesseraRestApplication implements com.quorum.tessera.config.apps.TesseraApp {

    public Q2TRestApp() {
    }

    @Override
    public Set<Object> getSingletons() {

        TransactionManagerFactory transactionManagerFactory = TransactionManagerFactory.create();
        EncodedPayloadManager encodedPayloadManager = EncodedPayloadManager.getInstance().orElseThrow(() -> new IllegalStateException("EncodedPayloadManager has not been initialised"));
        TransactionManager transactionManager = transactionManagerFactory.transactionManager().get();

        TransactionResource transactionResource = new TransactionResource(transactionManager);
        RawTransactionResource rawTransactionResource = new RawTransactionResource(transactionManager);
        EncodedPayloadResource encodedPayloadResource
            = new EncodedPayloadResource(encodedPayloadManager, transactionManager);

        return Set.of(transactionResource, rawTransactionResource, encodedPayloadResource);
    }

    @Override
    public AppType getAppType() {
        return AppType.Q2T;
    }
}
