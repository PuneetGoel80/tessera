package com.quorum.tessera.p2p.recovery;

import com.quorum.tessera.enclave.PayloadEncoder;
import com.quorum.tessera.recovery.resend.ResendBatchPublisher;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

public class ResendBatchPublisherProviderTest {

    @Test
    public void provider() {
        try(var recoveryClientMockedStatic= mockStatic(RecoveryClient.class);
            var payloadEncoderMockedStatic = mockStatic(PayloadEncoder.class)
        ) {

            recoveryClientMockedStatic.when(RecoveryClient::create).thenReturn(mock(RecoveryClient.class));
            payloadEncoderMockedStatic.when(PayloadEncoder::create).thenReturn(mock(PayloadEncoder.class));

            ResendBatchPublisher resendBatchPublisher = ResendBatchPublisherProvider.provider();
            assertThat(resendBatchPublisher).isNotNull().isExactlyInstanceOf(RestResendBatchPublisher.class);

            recoveryClientMockedStatic.verify(RecoveryClient::create);
            recoveryClientMockedStatic.verifyNoMoreInteractions();

            payloadEncoderMockedStatic.verify(PayloadEncoder::create);
            payloadEncoderMockedStatic.verifyNoMoreInteractions();
        }
    }

    @Test
    public void defaultConstructorForCoverage() {
        assertThat(new ResendBatchPublisherProvider()).isNotNull();
    }

}
