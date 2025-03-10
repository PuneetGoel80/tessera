package com.quorum.tessera.transaction.internal;

import static java.util.Collections.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.quorum.tessera.data.*;
import com.quorum.tessera.enclave.*;
import com.quorum.tessera.encryption.EncryptorException;
import com.quorum.tessera.encryption.Nonce;
import com.quorum.tessera.encryption.PublicKey;
import com.quorum.tessera.transaction.*;
import com.quorum.tessera.transaction.exception.MandatoryRecipientsNotAvailableException;
import com.quorum.tessera.transaction.exception.PrivacyViolationException;
import com.quorum.tessera.transaction.exception.RecipientKeyNotFoundException;
import com.quorum.tessera.transaction.exception.TransactionNotFoundException;
import com.quorum.tessera.transaction.publish.BatchPayloadPublisher;
import com.quorum.tessera.transaction.resend.ResendManager;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class TransactionManagerTest {

  private TransactionManager transactionManager;

  private EncryptedTransactionDAO encryptedTransactionDAO;

  private EncryptedRawTransactionDAO encryptedRawTransactionDAO;

  private ResendManager resendManager;

  private Enclave enclave;

  private PayloadDigest mockDigest;

  private PrivacyHelper privacyHelper;

  private BatchPayloadPublisher batchPayloadPublisher;

  @Before
  public void onSetUp() {
    enclave = mock(Enclave.class);
    encryptedTransactionDAO = mock(EncryptedTransactionDAO.class);
    encryptedRawTransactionDAO = mock(EncryptedRawTransactionDAO.class);
    resendManager = mock(ResendManager.class);
    privacyHelper = new PrivacyHelperImpl(encryptedTransactionDAO, true);
    batchPayloadPublisher = mock(BatchPayloadPublisher.class);
    mockDigest = cipherText -> cipherText;

    transactionManager =
        new TransactionManagerImpl(
            enclave,
            encryptedTransactionDAO,
            encryptedRawTransactionDAO,
            resendManager,
            batchPayloadPublisher,
            privacyHelper,
            mockDigest);
  }

  @After
  public void onTearDown() {
    verifyNoMoreInteractions(enclave, resendManager, batchPayloadPublisher);
    verifyNoMoreInteractions(encryptedTransactionDAO);
  }

  @Test
  public void send() {

    EncodedPayload encodedPayload = mock(EncodedPayload.class);
    when(encodedPayload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());

    when(enclave.encryptPayload(any(), any(), any(), any())).thenReturn(encodedPayload);

    PublicKey sender = PublicKey.from("SENDER".getBytes());
    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());

    when(enclave.getPublicKeys()).thenReturn(Set.of(receiver));

    byte[] payload = Base64.getEncoder().encode("PAYLOAD".getBytes());

    SendRequest sendRequest = mock(SendRequest.class);
    when(sendRequest.getPayload()).thenReturn(payload);
    when(sendRequest.getSender()).thenReturn(sender);
    when(sendRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendRequest.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);

    SendResponse result = transactionManager.send(sendRequest);

    assertThat(result).isNotNull();
    assertThat(result.getTransactionHash().toString()).isEqualTo("Q0lQSEVSVEVYVA==");
    assertThat(result.getManagedParties()).containsExactly(receiver);

    verify(enclave).encryptPayload(any(), any(), any(), any());
    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class), any(Callable.class));
    verify(enclave).getForwardingKeys();
    verify(enclave).getPublicKeys();
  }

  @Test
  public void sendWithMandatoryRecipients() {
    EncodedPayload encodedPayload = mock(EncodedPayload.class);

    when(encodedPayload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());

    when(enclave.encryptPayload(any(), any(), any(), any())).thenReturn(encodedPayload);

    PublicKey sender = PublicKey.from("SENDER".getBytes());
    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());

    when(enclave.getPublicKeys()).thenReturn(Set.of(receiver));

    byte[] payload = Base64.getEncoder().encode("PAYLOAD".getBytes());

    SendRequest sendRequest = mock(SendRequest.class);
    when(sendRequest.getPayload()).thenReturn(payload);
    when(sendRequest.getSender()).thenReturn(sender);
    when(sendRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendRequest.getPrivacyMode()).thenReturn(PrivacyMode.MANDATORY_RECIPIENTS);
    when(sendRequest.getMandatoryRecipients()).thenReturn(Set.of(receiver));

    ArgumentCaptor<PrivacyMetadata> metadataArgCaptor =
        ArgumentCaptor.forClass(PrivacyMetadata.class);

    SendResponse result = transactionManager.send(sendRequest);

    assertThat(result).isNotNull();
    assertThat(result.getTransactionHash().toString()).isEqualTo("Q0lQSEVSVEVYVA==");
    assertThat(result.getManagedParties()).containsExactly(receiver);

    verify(enclave).encryptPayload(any(), any(), any(), metadataArgCaptor.capture());
    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class), any(Callable.class));
    verify(enclave).getForwardingKeys();
    verify(enclave).getPublicKeys();

    final PrivacyMetadata metadata = metadataArgCaptor.getValue();

    assertThat(metadata.getPrivacyMode()).isEqualTo(PrivacyMode.MANDATORY_RECIPIENTS);
    assertThat(metadata.getMandatoryRecipients()).containsExactly(receiver);
  }

  @Test
  public void sendAlsoWithPublishCallbackCoverage() {

    EncodedPayload encodedPayload = mock(EncodedPayload.class);

    when(encodedPayload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());

    when(enclave.encryptPayload(
            any(byte[].class), any(PublicKey.class), anyList(), any(PrivacyMetadata.class)))
        .thenReturn(encodedPayload);

    doAnswer(
            invocation -> {
              Callable callable = invocation.getArgument(1);
              callable.call();
              return mock(EncryptedTransaction.class);
            })
        .when(encryptedTransactionDAO)
        .save(any(EncryptedTransaction.class), any(Callable.class));

    PublicKey sender = PublicKey.from("SENDER".getBytes());
    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());

    byte[] payload = Base64.getEncoder().encode("PAYLOAD".getBytes());

    SendRequest sendRequest = mock(SendRequest.class);
    when(sendRequest.getPayload()).thenReturn(payload);
    when(sendRequest.getSender()).thenReturn(sender);
    when(sendRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendRequest.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);

    SendResponse result = transactionManager.send(sendRequest);

    assertThat(result).isNotNull();
    assertThat(result.getTransactionHash().toString()).isEqualTo("Q0lQSEVSVEVYVA==");
    assertThat(result.getManagedParties()).isEmpty();

    verify(enclave)
        .encryptPayload(
            any(byte[].class), any(PublicKey.class), anyList(), any(PrivacyMetadata.class));
    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class), any(Callable.class));
    verify(enclave).getForwardingKeys();
    verify(enclave).getPublicKeys();
    verify(batchPayloadPublisher).publishPayload(any(), anyList());
  }

  @Test
  public void sendWithDuplicateRecipients() {
    EncodedPayload encodedPayload = mock(EncodedPayload.class);

    when(encodedPayload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());

    when(enclave.encryptPayload(any(), any(), any(), any())).thenReturn(encodedPayload);
    when(enclave.getForwardingKeys()).thenReturn(Set.of(PublicKey.from("RECEIVER".getBytes())));

    PublicKey sender = PublicKey.from("SENDER".getBytes());
    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());

    byte[] payload = Base64.getEncoder().encode("PAYLOAD".getBytes());

    SendRequest sendRequest = mock(SendRequest.class);
    when(sendRequest.getPayload()).thenReturn(payload);
    when(sendRequest.getSender()).thenReturn(sender);
    when(sendRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendRequest.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);

    SendResponse result = transactionManager.send(sendRequest);

    assertThat(result).isNotNull();
    assertThat(result.getTransactionHash().toString()).isEqualTo("Q0lQSEVSVEVYVA==");
    assertThat(result.getManagedParties()).isEmpty();

    verify(enclave).encryptPayload(any(), any(), any(), any());
    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class), any(Callable.class));
    verify(enclave).getForwardingKeys();
    verify(enclave).getPublicKeys();
  }

  @Test
  public void sendSignedTransaction() {

    EncodedPayload payload = mock(EncodedPayload.class);
    MessageHash hash = new MessageHash("HASH".getBytes());

    EncryptedRawTransaction encryptedRawTransaction = mock(EncryptedRawTransaction.class);
    when(encryptedRawTransaction.getHash()).thenReturn(hash);

    when(encryptedRawTransaction.toRawTransaction()).thenReturn(mock((RawTransaction.class)));

    when(encryptedRawTransactionDAO.retrieveByHash(hash))
        .thenReturn(Optional.of(encryptedRawTransaction));

    when(payload.getCipherText()).thenReturn("ENCRYPTED_PAYLOAD".getBytes());

    when(enclave.encryptPayload(any(RawTransaction.class), any(), any())).thenReturn(payload);

    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());

    when(enclave.getPublicKeys()).thenReturn(Set.of(receiver));

    SendSignedRequest sendSignedRequest = mock(SendSignedRequest.class);
    when(sendSignedRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendSignedRequest.getSignedData()).thenReturn("HASH".getBytes());
    when(sendSignedRequest.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);

    SendResponse result = transactionManager.sendSignedTransaction(sendSignedRequest);

    assertThat(result).isNotNull();
    assertThat(result.getTransactionHash()).isEqualTo(new MessageHash("HASH".getBytes()));
    assertThat(result.getManagedParties()).containsExactly(receiver);

    ArgumentCaptor<PrivacyMetadata> data = ArgumentCaptor.forClass(PrivacyMetadata.class);

    verify(enclave).encryptPayload(any(RawTransaction.class), any(), data.capture());
    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class), any(Callable.class));
    verify(encryptedRawTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getForwardingKeys();
    verify(enclave).getPublicKeys();

    final PrivacyMetadata passingData = data.getValue();
    assertThat(passingData.getPrivacyMode()).isEqualTo(PrivacyMode.STANDARD_PRIVATE);
    assertThat(passingData.getPrivacyGroupId()).isNotPresent();
  }

  @Test
  public void sendSignedTransactionWithMandatoryRecipients() {

    EncodedPayload payload = mock(EncodedPayload.class);
    MessageHash hash = new MessageHash("HASH".getBytes());

    EncryptedRawTransaction encryptedRawTransaction = mock(EncryptedRawTransaction.class);
    when(encryptedRawTransaction.getHash()).thenReturn(hash);
    when(encryptedRawTransaction.toRawTransaction()).thenReturn(mock((RawTransaction.class)));

    when(encryptedRawTransactionDAO.retrieveByHash(eq(hash)))
        .thenReturn(Optional.of(encryptedRawTransaction));

    when(payload.getCipherText()).thenReturn("ENCRYPTED_PAYLOAD".getBytes());

    when(enclave.encryptPayload(any(RawTransaction.class), any(), any())).thenReturn(payload);

    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());

    when(enclave.getPublicKeys()).thenReturn(Set.of(receiver));

    SendSignedRequest sendSignedRequest = mock(SendSignedRequest.class);
    when(sendSignedRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendSignedRequest.getSignedData()).thenReturn("HASH".getBytes());
    when(sendSignedRequest.getPrivacyMode()).thenReturn(PrivacyMode.MANDATORY_RECIPIENTS);
    when(sendSignedRequest.getMandatoryRecipients()).thenReturn(Set.of(receiver));

    SendResponse result = transactionManager.sendSignedTransaction(sendSignedRequest);

    assertThat(result).isNotNull();
    assertThat(result.getTransactionHash()).isEqualTo(new MessageHash("HASH".getBytes()));
    assertThat(result.getManagedParties()).containsExactly(receiver);

    ArgumentCaptor<PrivacyMetadata> data = ArgumentCaptor.forClass(PrivacyMetadata.class);

    verify(enclave).encryptPayload(any(RawTransaction.class), any(), data.capture());
    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class), any(Callable.class));
    verify(encryptedRawTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getForwardingKeys();
    verify(enclave).getPublicKeys();

    final PrivacyMetadata passingData = data.getValue();
    assertThat(passingData.getPrivacyMode()).isEqualTo(PrivacyMode.MANDATORY_RECIPIENTS);
    assertThat(passingData.getPrivacyGroupId()).isNotPresent();
    assertThat(passingData.getMandatoryRecipients()).containsExactly(receiver);
  }

  @Test
  public void sendSignedTransactionWithCallbackCoverage() {

    EncodedPayload payload = mock(EncodedPayload.class);
    MessageHash hash = new MessageHash("HASH".getBytes());

    EncryptedRawTransaction encryptedRawTransaction = mock(EncryptedRawTransaction.class);
    when(encryptedRawTransaction.getHash()).thenReturn(hash);
    when(encryptedRawTransaction.toRawTransaction()).thenReturn(mock((RawTransaction.class)));

    when(encryptedRawTransactionDAO.retrieveByHash(eq(hash)))
        .thenReturn(Optional.of(encryptedRawTransaction));

    doAnswer(
            invocation -> {
              Callable callable = invocation.getArgument(1);
              callable.call();
              return mock(EncryptedTransaction.class);
            })
        .when(encryptedTransactionDAO)
        .save(any(EncryptedTransaction.class), any(Callable.class));

    when(payload.getCipherText()).thenReturn("ENCRYPTED_PAYLOAD".getBytes());

    when(enclave.encryptPayload(any(RawTransaction.class), any(), any())).thenReturn(payload);

    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());

    SendSignedRequest sendSignedRequest = mock(SendSignedRequest.class);
    when(sendSignedRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendSignedRequest.getSignedData()).thenReturn("HASH".getBytes());
    when(sendSignedRequest.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(sendSignedRequest.getAffectedContractTransactions()).thenReturn(emptySet());
    when(sendSignedRequest.getExecHash()).thenReturn("execHash".getBytes());
    when(sendSignedRequest.getPrivacyGroupId())
        .thenReturn(Optional.of(PrivacyGroup.Id.fromBytes("group".getBytes())));

    SendResponse result = transactionManager.sendSignedTransaction(sendSignedRequest);

    assertThat(result).isNotNull();
    assertThat(result.getTransactionHash()).isEqualTo(new MessageHash("HASH".getBytes()));
    assertThat(result.getManagedParties()).isEmpty();

    ArgumentCaptor<PrivacyMetadata> data = ArgumentCaptor.forClass(PrivacyMetadata.class);

    verify(enclave).encryptPayload(any(RawTransaction.class), any(), data.capture());
    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class), any(Callable.class));
    verify(encryptedRawTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getForwardingKeys();
    verify(enclave).getPublicKeys();
    verify(batchPayloadPublisher).publishPayload(any(), anyList());

    final PrivacyMetadata passingData = data.getValue();
    assertThat(passingData.getPrivacyMode()).isEqualTo(PrivacyMode.PRIVATE_STATE_VALIDATION);
    assertThat(passingData.getAffectedContractTransactions()).isEmpty();
    assertThat(passingData.getExecHash()).isEqualTo("execHash".getBytes());
    assertThat(passingData.getPrivacyGroupId())
        .isPresent()
        .get()
        .isEqualTo(PrivacyGroup.Id.fromBytes("group".getBytes()));
  }

  @Test
  public void sendSignedTransactionWithDuplicateRecipients() {
    EncodedPayload payload = mock(EncodedPayload.class);
    MessageHash hash = new MessageHash("HASH".getBytes());

    EncryptedRawTransaction encryptedRawTransaction = mock(EncryptedRawTransaction.class);
    when(encryptedRawTransaction.getHash()).thenReturn(hash);
    when(encryptedRawTransaction.toRawTransaction()).thenReturn(mock((RawTransaction.class)));

    when(encryptedRawTransactionDAO.retrieveByHash(eq(hash)))
        .thenReturn(Optional.of(encryptedRawTransaction));

    when(payload.getCipherText()).thenReturn("ENCRYPTED_PAYLOAD".getBytes());
    when(enclave.getForwardingKeys()).thenReturn(Set.of(PublicKey.from("RECEIVER".getBytes())));
    when(enclave.encryptPayload(any(RawTransaction.class), any(), any())).thenReturn(payload);

    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());

    SendSignedRequest sendSignedRequest = mock(SendSignedRequest.class);
    when(sendSignedRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendSignedRequest.getSignedData()).thenReturn("HASH".getBytes());
    when(sendSignedRequest.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);

    SendResponse result = transactionManager.sendSignedTransaction(sendSignedRequest);

    assertThat(result).isNotNull();
    assertThat(result.getTransactionHash()).isEqualTo(new MessageHash("HASH".getBytes()));
    assertThat(result.getManagedParties()).isEmpty();

    verify(enclave).encryptPayload(any(RawTransaction.class), any(), any());
    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class), any(Callable.class));
    verify(encryptedRawTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getForwardingKeys();
    verify(enclave).getPublicKeys();
  }

  @Test
  public void sendSignedTransactionNoRawTransactionFoundException() {

    when(encryptedRawTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.empty());

    PublicKey receiver = PublicKey.from("RECEIVER".getBytes());
    SendSignedRequest sendSignedRequest = mock(SendSignedRequest.class);
    when(sendSignedRequest.getRecipients()).thenReturn(List.of(receiver));
    when(sendSignedRequest.getSignedData()).thenReturn("HASH".getBytes());

    try {
      transactionManager.sendSignedTransaction(sendSignedRequest);
      failBecauseExceptionWasNotThrown(TransactionNotFoundException.class);
    } catch (TransactionNotFoundException ex) {
      verify(encryptedRawTransactionDAO).retrieveByHash(any(MessageHash.class));
      verify(enclave).getForwardingKeys();
    }
  }

  @Test
  public void delete() {

    MessageHash messageHash = mock(MessageHash.class);

    transactionManager.delete(messageHash);

    verify(encryptedTransactionDAO).delete(messageHash);
  }

  @Test
  public void testDeleteAllDeletesTransactions() {
    PublicKey pk = PublicKey.from("test".getBytes(StandardCharsets.UTF_8));
    transactionManager.deleteAll(pk);
    verify(encryptedTransactionDAO, times(1)).deleteAll(pk);
  }

  @Test
  public void storePayloadAsRecipient() {
    EncodedPayload payload = mock(EncodedPayload.class);

    when(payload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());
    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.empty());

    transactionManager.storePayload(payload);

    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class));
    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(EncodedPayload.class), anyList());
  }

  @Test
  public void storePayloadWhenWeAreSender() {
    final PublicKey senderKey = PublicKey.from("SENDER".getBytes());

    final EncodedPayload encodedPayload = mock(EncodedPayload.class);
    when(encodedPayload.getSenderKey()).thenReturn(senderKey);
    when(encodedPayload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());
    when(encodedPayload.getRecipientBoxes()).thenReturn(new ArrayList<>());
    when(encodedPayload.getRecipientKeys()).thenReturn(new ArrayList<>());

    when(enclave.getPublicKeys()).thenReturn(singleton(senderKey));

    transactionManager.storePayload(encodedPayload);

    verify(resendManager).acceptOwnMessage(encodedPayload);
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(EncodedPayload.class), anyList());
  }

  @Test
  public void storePayloadWhenWeAreSenderWithPrivateStateConsensus() {
    final PublicKey senderKey = PublicKey.from("SENDER".getBytes());

    final EncodedPayload encodedPayload = mock(EncodedPayload.class);
    when(encodedPayload.getSenderKey()).thenReturn(senderKey);
    when(encodedPayload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());
    when(encodedPayload.getCipherTextNonce()).thenReturn(null);
    when(encodedPayload.getRecipientBoxes()).thenReturn(emptyList());
    when(encodedPayload.getRecipientNonce()).thenReturn(null);
    when(encodedPayload.getRecipientKeys()).thenReturn(emptyList());
    when(encodedPayload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(encodedPayload.getAffectedContractTransactions()).thenReturn(emptyMap());
    when(encodedPayload.getExecHash()).thenReturn(new byte[0]);

    when(enclave.getPublicKeys()).thenReturn(singleton(senderKey));

    transactionManager.storePayload(encodedPayload);

    verify(resendManager).acceptOwnMessage(encodedPayload);

    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(), any());
  }

  @Test
  public void storePayloadAsRecipientWithPrivateStateConsensus() {
    EncodedPayload payload = mock(EncodedPayload.class);

    when(payload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.empty());

    transactionManager.storePayload(payload);

    verify(encryptedTransactionDAO).save(any(EncryptedTransaction.class));
    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(), any());
  }

  @Test
  public void storePayloadAsRecipientWithAffectedContractTxsButPsvFlagMismatched() {

    final byte[] affectedContractPayload = "SOMEOTHERDATA".getBytes();
    final PublicKey senderKey = PublicKey.from("sender".getBytes());

    final EncodedPayload payload = mock(EncodedPayload.class);
    final EncryptedTransaction affectedContractTx = mock(EncryptedTransaction.class);
    final EncodedPayload affectedContractEncodedPayload = mock(EncodedPayload.class);

    Map<TxHash, SecurityHash> affectedContractTransactionHashes = new HashMap<>();
    affectedContractTransactionHashes.put(
        new TxHash(
            "bfMIqWJ/QGQhkK4USxMBxduzfgo/SIGoCros5bWYfPKUBinlAUCqLVOUAP9q+BgLlsWni1M6rnzfmaqSw2J5hQ=="),
        SecurityHash.from("securityHash".getBytes()));

    when(affectedContractTx.getPayload()).thenReturn(affectedContractEncodedPayload);
    when(affectedContractTx.getHash())
        .thenReturn(
            new MessageHash(
                new TxHash(
                        "bfMIqWJ/QGQhkK4USxMBxduzfgo/SIGoCros5bWYfPKUBinlAUCqLVOUAP9q+BgLlsWni1M6rnzfmaqSw2J5hQ==")
                    .getBytes()));
    when(payload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);
    when(affectedContractEncodedPayload.getPrivacyMode())
        .thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(payload.getAffectedContractTransactions()).thenReturn(affectedContractTransactionHashes);
    when(payload.getSenderKey()).thenReturn(senderKey);
    when(affectedContractEncodedPayload.getRecipientKeys()).thenReturn(List.of(senderKey));

    when(encryptedTransactionDAO.findByHashes(any())).thenReturn(List.of(affectedContractTx));
    when(affectedContractTx.getEncodedPayload()).thenReturn(affectedContractPayload);

    transactionManager.storePayload(payload);
    // Ignore transaction - not save
    verify(encryptedTransactionDAO).findByHashes(any());
  }

  @Test
  public void storePayloadSenderNotGenuineACOTHNotFound() {
    final byte[] input = "SOMEDATA".getBytes();
    final byte[] affectedContractPayload = "SOMEOTHERDATA".getBytes();
    final PublicKey senderKey = PublicKey.from("sender".getBytes());

    final EncodedPayload payload = mock(EncodedPayload.class);
    final EncryptedTransaction affectedContractTx = mock(EncryptedTransaction.class);
    final EncodedPayload affectedContractEncodedPayload = mock(EncodedPayload.class);

    final TxHash txHash =
        new TxHash(
            "bfMIqWJ/QGQhkK4USxMBxduzfgo/SIGoCros5bWYfPKUBinlAUCqLVOUAP9q+BgLlsWni1M6rnzfmaqSw2J5hQ==");

    Map<TxHash, SecurityHash> affectedContractTransactionHashes = new HashMap<>();
    affectedContractTransactionHashes.put(txHash, SecurityHash.from("securityHash".getBytes()));
    affectedContractTransactionHashes.put(
        new TxHash(
            "bfMIqWJ/QGQhkK4USxMBxduzfgo/SIGoCros5bWYfPKUBinlAUCqLVOUAP9q+BgLlsWni1M6rnzfmaqSr5J5hQ=="),
        SecurityHash.from("bogus".getBytes()));

    when(affectedContractTx.getEncodedPayload()).thenReturn(input);
    when(payload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(affectedContractEncodedPayload.getPrivacyMode())
        .thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(payload.getAffectedContractTransactions()).thenReturn(affectedContractTransactionHashes);
    when(payload.getSenderKey()).thenReturn(senderKey);
    when(affectedContractEncodedPayload.getRecipientKeys()).thenReturn(List.of(senderKey));

    when(encryptedTransactionDAO.findByHashes(List.of(new MessageHash(txHash.getBytes()))))
        .thenReturn(List.of(affectedContractTx));
    when(affectedContractTx.getEncodedPayload()).thenReturn(affectedContractPayload);

    transactionManager.storePayload(payload);
    // Ignore transaction - not save
    verify(encryptedTransactionDAO, times(0)).save(any(EncryptedTransaction.class));
    verify(encryptedTransactionDAO).findByHashes(any());
  }

  @Test
  public void storePayloadSenderNotInRecipientList() {
    final byte[] input = "SOMEDATA".getBytes();
    final byte[] affectedContractPayload = "SOMEOTHERDATA".getBytes();
    final PublicKey senderKey = PublicKey.from("sender".getBytes());
    final PublicKey someOtherKey = PublicKey.from("otherKey".getBytes());

    final EncodedPayload payload = mock(EncodedPayload.class);
    final EncryptedTransaction affectedContractTx = mock(EncryptedTransaction.class);
    final EncodedPayload affectedContractEncodedPayload = mock(EncodedPayload.class);

    final TxHash txHash =
        new TxHash(
            "bfMIqWJ/QGQhkK4USxMBxduzfgo/SIGoCros5bWYfPKUBinlAUCqLVOUAP9q+BgLlsWni1M6rnzfmaqSw2J5hQ==");

    Map<TxHash, SecurityHash> affectedContractTransactionHashes = new HashMap<>();
    affectedContractTransactionHashes.put(txHash, SecurityHash.from("securityHash".getBytes()));
    affectedContractTransactionHashes.put(
        new TxHash(
            "bfMIqWJ/QGQhkK4USxMBxduzfgo/SIGoCros5bWYfPKUBinlAUCqLVOUAP9q+BgLlsWni1M6rnzfmaqSr5J5hQ=="),
        SecurityHash.from("bogus".getBytes()));

    when(affectedContractTx.getEncodedPayload()).thenReturn(input);
    when(affectedContractTx.getHash()).thenReturn(new MessageHash(txHash.getBytes()));
    when(payload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(affectedContractEncodedPayload.getPrivacyMode())
        .thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(payload.getAffectedContractTransactions()).thenReturn(affectedContractTransactionHashes);
    when(payload.getSenderKey()).thenReturn(senderKey);
    when(affectedContractEncodedPayload.getRecipientKeys()).thenReturn(List.of(someOtherKey));

    when(encryptedTransactionDAO.findByHashes(any())).thenReturn(List.of(affectedContractTx));
    when(affectedContractTx.getPayload()).thenReturn(affectedContractEncodedPayload);

    transactionManager.storePayload(payload);
    // Ignore transaction - not save
    verify(encryptedTransactionDAO).findByHashes(any());
  }

  @Test
  public void storePayloadPsvWithInvalidSecurityHashes() {

    EncodedPayload payload = mock(EncodedPayload.class);
    when(payload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);

    when(enclave.findInvalidSecurityHashes(any(), any()))
        .thenReturn(singleton(new TxHash("invalidHash".getBytes())));

    assertThatExceptionOfType(PrivacyViolationException.class)
        .describedAs("There are privacy violation for psv")
        .isThrownBy(() -> transactionManager.storePayload(payload))
        .withMessageContaining("Invalid security hashes identified for PSC TX");

    verify(enclave).findInvalidSecurityHashes(any(), any());
  }

  @Test
  public void storePayloadWithInvalidSecurityHashesIgnoreIfNotPsv() {
    Map<TxHash, SecurityHash> affectedTx =
        Map.of(TxHash.from("invalidHash".getBytes()), SecurityHash.from("security".getBytes()));

    final EncodedPayload payload = mock(EncodedPayload.class);
    when(payload.getSenderKey()).thenReturn(PublicKey.from("sender".getBytes()));
    when(payload.getCipherText()).thenReturn("CIPHERTEXT".getBytes());
    when(payload.getCipherTextNonce()).thenReturn(new Nonce("nonce".getBytes()));
    when(payload.getRecipientBoxes()).thenReturn(List.of(RecipientBox.from("box1".getBytes())));
    when(payload.getRecipientNonce()).thenReturn(new Nonce("recipientNonce".getBytes()));
    when(payload.getRecipientKeys())
        .thenReturn(singletonList(PublicKey.from("recipient".getBytes())));
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.PARTY_PROTECTION);
    when(payload.getAffectedContractTransactions()).thenReturn(affectedTx);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.empty());

    ArgumentCaptor<EncryptedTransaction> txCaptor =
        ArgumentCaptor.forClass(EncryptedTransaction.class);
    when(enclave.findInvalidSecurityHashes(any(), any()))
        .thenReturn(singleton(new TxHash("invalidHash".getBytes())));

    transactionManager.storePayload(payload);

    verify(encryptedTransactionDAO).save(txCaptor.capture());

    EncodedPayload sanitisedPayload = txCaptor.getValue().getPayload();

    // Assert that the invalid ACOTH had been removed
    assertThat(
            sanitisedPayload
                .getAffectedContractTransactions()
                .get(TxHash.from("invalidHash".getBytes())))
        .isNull();

    verify(encryptedTransactionDAO).findByHashes(any());
    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(), any());
  }

  @Test
  public void storePayloadWithExistingRecipientAndMismatchedContents() {

    EncryptedTransaction existingDatabaseEntry = mock(EncryptedTransaction.class);
    EncodedPayload existingPayload = mock(EncodedPayload.class);
    when(existingPayload.getCipherText()).thenReturn("ct1".getBytes());
    when(existingDatabaseEntry.getPayload()).thenReturn(existingPayload);
    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(existingDatabaseEntry));

    EncodedPayload payloadToStore = mock(EncodedPayload.class);
    when(payloadToStore.getCipherText()).thenReturn("ct2".getBytes());

    final Throwable throwable =
        catchThrowable(() -> transactionManager.storePayload(payloadToStore));

    assertThat(throwable)
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Invalid existing transaction");

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(EncodedPayload.class), anyList());
  }

  @Test
  public void storePayloadWithExistingRecipientPSVRecipientNotFound() {
    EncryptedTransaction existingDatabaseEntry = mock(EncryptedTransaction.class);

    EncodedPayload existingPayload = mock(EncodedPayload.class);
    when(existingPayload.getCipherText()).thenReturn("ct1".getBytes());
    when(existingPayload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(existingPayload.getExecHash()).thenReturn("execHash".getBytes());

    when(existingDatabaseEntry.getPayload()).thenReturn(existingPayload);
    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(existingDatabaseEntry));

    EncodedPayload payloadToStore = mock(EncodedPayload.class);
    when(payloadToStore.getCipherText()).thenReturn("ct1".getBytes());
    when(payloadToStore.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(payloadToStore.getExecHash()).thenReturn("execHash".getBytes());
    when(payloadToStore.getRecipientKeys())
        .thenReturn(List.of(PublicKey.from("recipient1".getBytes())));
    when(payloadToStore.getRecipientBoxes())
        .thenReturn(List.of(RecipientBox.from("recipient_box1".getBytes())));

    final Throwable throwable =
        catchThrowable(() -> transactionManager.storePayload(payloadToStore));

    assertThat(throwable)
        .isInstanceOf(RuntimeException.class)
        .hasMessage("expected recipient not found");

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(EncodedPayload.class), anyList());
  }

  @Test
  public void storePayloadWithExistingRecipientPSV() {
    privacyHelper = mock(PrivacyHelper.class);
    transactionManager =
        new TransactionManagerImpl(
            enclave,
            encryptedTransactionDAO,
            encryptedRawTransactionDAO,
            resendManager,
            batchPayloadPublisher,
            privacyHelper,
            mockDigest);

    when(privacyHelper.validatePayload(any(), any(), any())).thenReturn(true);

    PublicKey recipient1 = PublicKey.from("recipient1".getBytes());
    PublicKey recipient2 = PublicKey.from("recipient2".getBytes());

    TxHash txHash = TxHash.from("txHash".getBytes());
    SecurityHash securityHash = SecurityHash.from("securityHash".getBytes());

    EncodedPayload existingPayload = mock(EncodedPayload.class);
    when(existingPayload.getCipherText()).thenReturn("ct1".getBytes());
    when(existingPayload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(existingPayload.getAffectedContractTransactions())
        .thenReturn(Map.of(txHash, securityHash));
    when(existingPayload.getExecHash()).thenReturn("execHash".getBytes());
    when(existingPayload.getRecipientKeys()).thenReturn(List.of(recipient1, recipient2));

    EncryptedTransaction existingDatabaseEntry = new EncryptedTransaction(null, existingPayload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(existingDatabaseEntry));

    EncodedPayload payloadToStore = mock(EncodedPayload.class);
    when(payloadToStore.getCipherText()).thenReturn("ct1".getBytes());
    when(payloadToStore.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(payloadToStore.getAffectedContractTransactions()).thenReturn(Map.of(txHash, securityHash));
    when(payloadToStore.getExecHash()).thenReturn("execHash".getBytes());
    when(payloadToStore.getRecipientKeys()).thenReturn(List.of(recipient1, recipient2));
    when(payloadToStore.getRecipientBoxes())
        .thenReturn(List.of(RecipientBox.from("recipient_box1".getBytes())));

    MessageHash response = transactionManager.storePayload(payloadToStore);

    assertThat(response.toString()).isEqualTo("Y3Qx");

    ArgumentCaptor<EncryptedTransaction> txCaptor =
        ArgumentCaptor.forClass(EncryptedTransaction.class);
    verify(encryptedTransactionDAO).update(txCaptor.capture());

    EncodedPayload updatedTransaction = txCaptor.getValue().getPayload();
    assertThat(updatedTransaction.getRecipientKeys()).containsExactly(recipient1, recipient2);
    assertThat(updatedTransaction.getRecipientBoxes())
        .containsExactly(RecipientBox.from("recipient_box1".getBytes()));

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(EncodedPayload.class), anyList());
  }

  @Test
  public void storePayloadWithExistingRecipientNonPSV() {
    PublicKey recipient1 = PublicKey.from("recipient1".getBytes());
    PublicKey recipient2 = PublicKey.from("recipient2".getBytes());

    EncodedPayload existingPayload = mock(EncodedPayload.class);
    when(existingPayload.getCipherText()).thenReturn("ct1".getBytes());
    when(existingPayload.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);
    when(existingPayload.getRecipientKeys()).thenReturn(List.of(recipient1));
    when(existingPayload.getRecipientBoxes())
        .thenReturn(List.of(RecipientBox.from("recipient_box1".getBytes())));

    EncryptedTransaction existingDatabaseEntry =
        new EncryptedTransaction(mock(MessageHash.class), existingPayload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(existingDatabaseEntry));

    EncodedPayload payloadToStore = mock(EncodedPayload.class);
    when(payloadToStore.getCipherText()).thenReturn("ct1".getBytes());
    when(payloadToStore.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);
    when(payloadToStore.getRecipientKeys()).thenReturn(List.of(recipient2));
    when(payloadToStore.getRecipientBoxes())
        .thenReturn(List.of(RecipientBox.from("recipient_box2".getBytes())));

    MessageHash response = transactionManager.storePayload(payloadToStore);

    assertThat(response.toString()).isEqualTo("Y3Qx");

    ArgumentCaptor<EncryptedTransaction> txCaptor =
        ArgumentCaptor.forClass(EncryptedTransaction.class);
    verify(encryptedTransactionDAO).update(txCaptor.capture());

    EncodedPayload updatedTransaction = txCaptor.getValue().getPayload();
    assertThat(updatedTransaction.getRecipientKeys()).containsExactly(recipient2, recipient1);
    assertThat(updatedTransaction.getRecipientBoxes())
        .containsExactly(
            RecipientBox.from("recipient_box2".getBytes()),
            RecipientBox.from("recipient_box1".getBytes()));

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(EncodedPayload.class), anyList());
  }

  @Test
  public void storePayloadWithDuplicateExistingRecipient() {
    PublicKey recipient1 = PublicKey.from("recipient1".getBytes());

    EncryptedTransaction existingDatabaseEntry = mock(EncryptedTransaction.class);

    EncodedPayload existingPayload = mock(EncodedPayload.class);
    when(existingPayload.getCipherText()).thenReturn("ct1".getBytes());
    when(existingPayload.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);
    when(existingPayload.getRecipientKeys()).thenReturn(List.of(recipient1));
    when(existingPayload.getRecipientBoxes())
        .thenReturn(List.of(RecipientBox.from("recipient_box1".getBytes())));

    when(existingDatabaseEntry.getPayload()).thenReturn(existingPayload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(existingDatabaseEntry));

    EncodedPayload payloadToStore = mock(EncodedPayload.class);
    when(payloadToStore.getCipherText()).thenReturn("ct1".getBytes());
    when(payloadToStore.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);
    when(payloadToStore.getRecipientKeys()).thenReturn(List.of(recipient1));
    when(payloadToStore.getRecipientBoxes())
        .thenReturn(List.of(RecipientBox.from("recipient_box1".getBytes())));

    MessageHash response = transactionManager.storePayload(payloadToStore);

    assertThat(response.toString()).isEqualTo("Y3Qx");

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(EncodedPayload.class), anyList());
  }

  @Test
  public void storePayloadWithExistingRecipientLegacyNoRecipients() {

    EncodedPayload existingPayload = mock(EncodedPayload.class);
    when(existingPayload.getCipherText()).thenReturn("ct1".getBytes());
    when(existingPayload.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);
    when(existingPayload.getRecipientBoxes())
        .thenReturn(List.of(RecipientBox.from("recipient_box1".getBytes())));

    EncryptedTransaction existingDatabaseEntry = new EncryptedTransaction(null, existingPayload);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.of(existingDatabaseEntry));

    EncodedPayload payloadToStore = mock(EncodedPayload.class);
    when(payloadToStore.getCipherText()).thenReturn("ct1".getBytes());
    when(payloadToStore.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);
    when(payloadToStore.getRecipientBoxes())
        .thenReturn(List.of(RecipientBox.from("recipient_box2".getBytes())));

    MessageHash response = transactionManager.storePayload(payloadToStore);

    assertThat(response.toString()).isEqualTo("Y3Qx");

    ArgumentCaptor<EncryptedTransaction> txCaptor =
        ArgumentCaptor.forClass(EncryptedTransaction.class);
    verify(encryptedTransactionDAO).update(txCaptor.capture());

    EncodedPayload updatedTransaction = txCaptor.getValue().getPayload();
    assertThat(updatedTransaction.getRecipientKeys()).isEmpty();
    assertThat(updatedTransaction.getRecipientBoxes())
        .containsExactly(
            RecipientBox.from("recipient_box2".getBytes()),
            RecipientBox.from("recipient_box1".getBytes()));

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave).getPublicKeys();
    verify(enclave).findInvalidSecurityHashes(any(EncodedPayload.class), anyList());
  }

  @Test
  public void receive() {
    PublicKey sender = PublicKey.from("sender".getBytes());
    PublicKey recipient = PublicKey.from("recipient".getBytes());

    MessageHash messageHash = new MessageHash("hash".getBytes());

    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    when(receiveRequest.getRecipient()).thenReturn(Optional.of(recipient));
    when(receiveRequest.getTransactionHash()).thenReturn(messageHash);

    EncodedPayload payload = mock(EncodedPayload.class);
    when(payload.getExecHash()).thenReturn("execHash".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(payload.getSenderKey()).thenReturn(sender);

    EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    when(encryptedTransaction.getHash()).thenReturn(messageHash);
    when(encryptedTransaction.getPayload()).thenReturn(payload);

    when(encryptedTransactionDAO.retrieveByHash(eq(messageHash)))
        .thenReturn(Optional.of(encryptedTransaction));

    byte[] expectedOutcome = "Encrypted payload".getBytes();

    when(enclave.unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class)))
        .thenReturn(expectedOutcome);

    PublicKey publicKey = mock(PublicKey.class);
    when(enclave.getPublicKeys()).thenReturn(Set.of(publicKey));

    ReceiveResponse receiveResponse = transactionManager.receive(receiveRequest);

    assertThat(receiveResponse).isNotNull();
    assertThat(receiveResponse.sender()).isEqualTo(sender);
    assertThat(receiveResponse.getUnencryptedTransactionData()).isEqualTo(expectedOutcome);
    assertThat(receiveResponse.getPrivacyGroupId()).isNotPresent();

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave, times(2)).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    verify(enclave).getPublicKeys();
  }

  @Test
  public void receiveWithPrivacyGroupId() {
    PublicKey sender = PublicKey.from("sender".getBytes());
    MessageHash messageHash = mock(MessageHash.class);

    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    when(receiveRequest.getRecipient()).thenReturn(Optional.of(mock(PublicKey.class)));
    when(receiveRequest.getTransactionHash()).thenReturn(messageHash);

    EncodedPayload payload = mock(EncodedPayload.class);
    when(payload.getExecHash()).thenReturn("execHash".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(payload.getSenderKey()).thenReturn(sender);
    when(payload.getPrivacyGroupId())
        .thenReturn(Optional.of(PrivacyGroup.Id.fromBytes("group".getBytes())));

    EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    when(encryptedTransaction.getHash()).thenReturn(messageHash);
    when(encryptedTransaction.getPayload()).thenReturn(payload);

    when(encryptedTransactionDAO.retrieveByHash(eq(messageHash)))
        .thenReturn(Optional.of(encryptedTransaction));

    byte[] expectedOutcome = "Encrypted payload".getBytes();

    when(enclave.unencryptTransaction(eq(payload), any(PublicKey.class)))
        .thenReturn(expectedOutcome);

    PublicKey publicKey = mock(PublicKey.class);
    when(enclave.getPublicKeys()).thenReturn(Set.of(publicKey));

    ReceiveResponse receiveResponse = transactionManager.receive(receiveRequest);

    assertThat(receiveResponse).isNotNull();
    assertThat(receiveResponse.sender()).isEqualTo(sender);
    assertThat(receiveResponse.getUnencryptedTransactionData()).isEqualTo(expectedOutcome);
    assertThat(receiveResponse.getPrivacyGroupId()).isPresent();
    assertThat(receiveResponse.getPrivacyGroupId().get())
        .isEqualTo(PrivacyGroup.Id.fromBytes("group".getBytes()));

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave, times(2)).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    verify(enclave).getPublicKeys();
  }

  @Test
  public void receiveWithMandatoryRecipients() {
    PublicKey sender = PublicKey.from("sender".getBytes());
    byte[] randomData = Base64.getEncoder().encode("odd-data".getBytes());
    MessageHash messageHash = mock(MessageHash.class);

    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    when(receiveRequest.getRecipient()).thenReturn(Optional.of(mock(PublicKey.class)));
    when(receiveRequest.getTransactionHash()).thenReturn(messageHash);

    PublicKey mandReceiver1 = PublicKey.from("mandatory1".getBytes());
    PublicKey mandReceiver2 = PublicKey.from("mandatory2".getBytes());
    EncodedPayload payload = mock(EncodedPayload.class);
    when(payload.getMandatoryRecipients()).thenReturn(Set.of(mandReceiver1, mandReceiver2));
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.MANDATORY_RECIPIENTS);
    when(payload.getSenderKey()).thenReturn(sender);
    when(payload.getPrivacyGroupId())
        .thenReturn(Optional.of(PrivacyGroup.Id.fromBytes("group".getBytes())));

    EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    when(encryptedTransaction.getHash()).thenReturn(messageHash);
    when(encryptedTransaction.getPayload()).thenReturn(payload);

    when(encryptedTransactionDAO.retrieveByHash(eq(messageHash)))
        .thenReturn(Optional.of(encryptedTransaction));

    byte[] expectedOutcome = "Encrypted payload".getBytes();

    when(enclave.unencryptTransaction(eq(payload), any(PublicKey.class)))
        .thenReturn(expectedOutcome);

    PublicKey publicKey = mock(PublicKey.class);
    when(enclave.getPublicKeys()).thenReturn(Set.of(publicKey));

    ReceiveResponse receiveResponse = transactionManager.receive(receiveRequest);

    assertThat(receiveResponse).isNotNull();
    assertThat(receiveResponse.sender()).isEqualTo(sender);
    assertThat(receiveResponse.getUnencryptedTransactionData()).isEqualTo(expectedOutcome);
    assertThat(receiveResponse.getPrivacyGroupId()).isPresent();
    assertThat(receiveResponse.getPrivacyGroupId().get())
        .isEqualTo(PrivacyGroup.Id.fromBytes("group".getBytes()));

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave, times(2)).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    verify(enclave).getPublicKeys();
  }

  @Test
  public void receiveWithRecipientsPresent() {
    final PublicKey sender = PublicKey.from("sender".getBytes());
    final PublicKey recipient1 = PublicKey.from("recipient1".getBytes());
    final PublicKey recipient2 = PublicKey.from("recipient2".getBytes());

    MessageHash messageHash = mock(MessageHash.class);

    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    when(receiveRequest.getRecipient()).thenReturn(Optional.of(mock(PublicKey.class)));
    when(receiveRequest.getTransactionHash()).thenReturn(messageHash);

    EncodedPayload payload = mock(EncodedPayload.class);
    when(payload.getExecHash()).thenReturn("execHash".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);
    when(payload.getRecipientKeys()).thenReturn(List.of(recipient1, recipient2));
    when(payload.getSenderKey()).thenReturn(sender);

    EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    when(encryptedTransaction.getHash()).thenReturn(messageHash);
    when(encryptedTransaction.getPayload()).thenReturn(payload);

    when(encryptedTransactionDAO.retrieveByHash(eq(messageHash)))
        .thenReturn(Optional.of(encryptedTransaction));

    byte[] expectedOutcome = "Encrypted payload".getBytes();

    when(enclave.unencryptTransaction(eq(payload), any(PublicKey.class)))
        .thenReturn(expectedOutcome);
    when(enclave.getPublicKeys()).thenReturn(Set.of(recipient1, recipient2));

    ReceiveResponse receiveResponse = transactionManager.receive(receiveRequest);

    assertThat(receiveResponse).isNotNull();
    assertThat(receiveResponse.getUnencryptedTransactionData()).isEqualTo(expectedOutcome);
    assertThat(receiveResponse.getManagedParties())
        .containsExactlyInAnyOrder(recipient1, recipient2);
    assertThat(receiveResponse.sender()).isEqualTo(sender);

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave, times(2)).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    verify(enclave, times(2)).getPublicKeys();
  }

  @Test
  public void receiveWithNoRecipientsPresent() {
    final PublicKey sender = PublicKey.from("sender".getBytes());
    final PublicKey recipient1 = PublicKey.from("recipient1".getBytes());

    MessageHash messageHash = mock(MessageHash.class);

    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    when(receiveRequest.getRecipient()).thenReturn(Optional.of(mock(PublicKey.class)));
    when(receiveRequest.getTransactionHash()).thenReturn(messageHash);

    EncodedPayload payload = mock(EncodedPayload.class);
    when(payload.getSenderKey()).thenReturn(sender);
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.STANDARD_PRIVATE);
    when(payload.getRecipientBoxes()).thenReturn(List.of(RecipientBox.from("box1".getBytes())));

    EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    when(encryptedTransaction.getHash()).thenReturn(messageHash);
    when(encryptedTransaction.getPayload()).thenReturn(payload);

    when(encryptedTransactionDAO.retrieveByHash(eq(messageHash)))
        .thenReturn(Optional.of(encryptedTransaction));

    byte[] expectedOutcome = "Encrypted payload".getBytes();

    when(enclave.unencryptTransaction(eq(payload), any(PublicKey.class)))
        .thenReturn(expectedOutcome);
    when(enclave.getPublicKeys()).thenReturn(Set.of(recipient1));

    ReceiveResponse receiveResponse = transactionManager.receive(receiveRequest);

    assertThat(receiveResponse).isNotNull();
    assertThat(receiveResponse.getUnencryptedTransactionData()).isEqualTo(expectedOutcome);
    assertThat(receiveResponse.getManagedParties()).containsExactly(recipient1);
    assertThat(receiveResponse.sender()).isEqualTo(sender);

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave, times(3)).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    verify(enclave, times(2)).getPublicKeys();
  }

  @Test
  public void receiveRawTransaction() {
    byte[] keyData = Base64.getEncoder().encode("KEY".getBytes());
    PublicKey recipient = PublicKey.from("recipient".getBytes());
    MessageHash messageHash = new MessageHash(Base64.getDecoder().decode(keyData));

    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    when(receiveRequest.getRecipient()).thenReturn(Optional.of(recipient));
    when(receiveRequest.getTransactionHash()).thenReturn(messageHash);
    when(receiveRequest.isRaw()).thenReturn(true);

    EncryptedRawTransaction encryptedTransaction = mock(EncryptedRawTransaction.class);
    when(encryptedTransaction.getEncryptedPayload()).thenReturn("payload".getBytes());
    when(encryptedTransaction.getEncryptedKey()).thenReturn("key".getBytes());
    when(encryptedTransaction.getNonce()).thenReturn("nonce".getBytes());
    when(encryptedTransaction.getSender()).thenReturn("sender".getBytes());

    when(encryptedRawTransactionDAO.retrieveByHash(messageHash))
        .thenReturn(Optional.of(encryptedTransaction));

    when(enclave.unencryptRawPayload(any(RawTransaction.class))).thenReturn("response".getBytes());

    ReceiveResponse response = transactionManager.receive(receiveRequest);

    assertThat(response.getUnencryptedTransactionData()).isEqualTo("response".getBytes());

    verify(enclave).unencryptRawPayload(any(RawTransaction.class));
  }

  @Test
  public void receiveRawTransactionNotFound() {

    MessageHash messageHash = mock(MessageHash.class);

    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    when(receiveRequest.getTransactionHash()).thenReturn(messageHash);
    when(receiveRequest.isRaw()).thenReturn(true);

    when(encryptedRawTransactionDAO.retrieveByHash(messageHash)).thenReturn(Optional.empty());

    assertThatExceptionOfType(TransactionNotFoundException.class)
        .isThrownBy(() -> transactionManager.receive(receiveRequest));
  }

  @Test
  public void receiveWithAffectedContractTransactions() {
    PublicKey sender = PublicKey.from("sender".getBytes());

    PublicKey recipient = PublicKey.from("recipient".getBytes());
    MessageHash messageHash = mock(MessageHash.class);

    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    when(receiveRequest.getRecipient()).thenReturn(Optional.of(recipient));
    when(receiveRequest.getTransactionHash()).thenReturn(messageHash);

    final String b64AffectedTxHash =
        "bfMIqWJ/QGQhkK4USxMBxduzfgo/SIGoCros5bWYfPKUBinlAUCqLVOUAP9q+BgLlsWni1M6rnzfmaqSw2J5hQ==";
    final Map<TxHash, SecurityHash> affectedTxs =
        Map.of(new TxHash(b64AffectedTxHash), SecurityHash.from("encoded".getBytes()));

    EncodedPayload payload = mock(EncodedPayload.class);
    when(payload.getExecHash()).thenReturn("execHash".getBytes());
    when(payload.getPrivacyMode()).thenReturn(PrivacyMode.PRIVATE_STATE_VALIDATION);
    when(payload.getAffectedContractTransactions()).thenReturn(affectedTxs);
    when(payload.getSenderKey()).thenReturn(sender);

    final EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    when(encryptedTransaction.getHash()).thenReturn(messageHash);
    when(encryptedTransaction.getPayload()).thenReturn(payload);

    when(encryptedTransactionDAO.retrieveByHash(eq(messageHash)))
        .thenReturn(Optional.of(encryptedTransaction));

    byte[] expectedOutcome = "Encrypted payload".getBytes();

    when(enclave.unencryptTransaction(eq(payload), any(PublicKey.class)))
        .thenReturn(expectedOutcome);

    PublicKey publicKey = mock(PublicKey.class);
    when(enclave.getPublicKeys()).thenReturn(Collections.singleton(publicKey));

    ReceiveResponse receiveResponse = transactionManager.receive(receiveRequest);

    assertThat(receiveResponse).isNotNull();

    assertThat(receiveResponse.getUnencryptedTransactionData()).isEqualTo(expectedOutcome);
    assertThat(receiveResponse.getExecHash()).isEqualTo("execHash".getBytes());
    assertThat(receiveResponse.getAffectedTransactions()).hasSize(1);
    assertThat(receiveResponse.sender()).isEqualTo(sender);

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    verify(enclave, times(2)).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    verify(enclave).getPublicKeys();
  }

  @Test
  public void receiveNoTransactionInDatabase() {

    PublicKey recipient = PublicKey.from("recipient".getBytes());

    MessageHash messageHash = mock(MessageHash.class);
    when(messageHash.getHashBytes()).thenReturn("KEY".getBytes());

    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    when(receiveRequest.getTransactionHash()).thenReturn(messageHash);
    when(receiveRequest.getRecipient()).thenReturn(Optional.of(recipient));

    EncodedPayload payload = mock(EncodedPayload.class);

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.empty());

    try {
      transactionManager.receive(receiveRequest);
      failBecauseExceptionWasNotThrown(TransactionNotFoundException.class);
    } catch (TransactionNotFoundException ex) {
      verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
    }
  }

  @Test
  public void receiveNoRecipientKeyFound() {

    PublicKey recipient = PublicKey.from("recipient".getBytes());

    MessageHash messageHash = mock(MessageHash.class);
    when(messageHash.getHashBytes()).thenReturn("KEY".getBytes());

    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    when(receiveRequest.getTransactionHash()).thenReturn(messageHash);
    when(receiveRequest.getRecipient()).thenReturn(Optional.of(recipient));

    EncodedPayload payload = mock(EncodedPayload.class);

    EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    when(encryptedTransaction.getPayload()).thenReturn(payload);
    when(encryptedTransaction.getHash()).thenReturn(messageHash);

    when(encryptedTransactionDAO.retrieveByHash(eq(messageHash)))
        .thenReturn(Optional.of(encryptedTransaction));

    PublicKey publicKey = mock(PublicKey.class);
    when(enclave.getPublicKeys()).thenReturn(Collections.singleton(publicKey));

    when(enclave.unencryptTransaction(eq(payload), any(PublicKey.class)))
        .thenThrow(EncryptorException.class);

    try {
      transactionManager.receive(receiveRequest);
      failBecauseExceptionWasNotThrown(RecipientKeyNotFoundException.class);
    } catch (RecipientKeyNotFoundException ex) {
      verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
      verify(enclave).getPublicKeys();
      verify(enclave).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    }
  }

  @Test
  public void receiveUnableToDecodePayload() {

    MessageHash messageHash = mock(MessageHash.class);

    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    when(receiveRequest.getRecipient()).thenReturn(Optional.of(mock(PublicKey.class)));
    when(receiveRequest.getTransactionHash()).thenReturn(messageHash);

    EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    when(encryptedTransaction.getHash()).thenReturn(messageHash);
    when(encryptedTransaction.getEncodedPayload()).thenReturn("bogus".getBytes());

    when(encryptedTransactionDAO.retrieveByHash(eq(messageHash)))
        .thenReturn(Optional.of(encryptedTransaction));

    when(enclave.getPublicKeys()).thenReturn(Set.of(mock(PublicKey.class)));

    final Throwable throwable = catchThrowable(() -> transactionManager.receive(receiveRequest));

    assertThat(throwable).isInstanceOf(IllegalStateException.class);

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
  }

  @Test
  public void receiveEmptyRecipientThrowsNoRecipientKeyFound() {

    MessageHash transactionHash = mock(MessageHash.class);

    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    when(receiveRequest.getRecipient()).thenReturn(Optional.empty());
    when(receiveRequest.getTransactionHash()).thenReturn(transactionHash);

    EncodedPayload payload = mock(EncodedPayload.class);

    EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    when(encryptedTransaction.getHash()).thenReturn(transactionHash);
    when(encryptedTransaction.getPayload()).thenReturn(payload);

    when(encryptedTransactionDAO.retrieveByHash(eq(transactionHash)))
        .thenReturn(Optional.of(encryptedTransaction));

    when(enclave.getPublicKeys()).thenReturn(Set.of(mock(PublicKey.class)));

    when(enclave.unencryptTransaction(eq(payload), any(PublicKey.class)))
        .thenThrow(EncryptorException.class);

    try {
      transactionManager.receive(receiveRequest);
      failBecauseExceptionWasNotThrown(RecipientKeyNotFoundException.class);
    } catch (RecipientKeyNotFoundException ex) {
      verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
      verify(enclave).getPublicKeys();
      verify(enclave).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    }
  }

  @Test
  public void receiveNullRecipientThrowsNoRecipientKeyFound() {

    MessageHash transactionHash = mock(MessageHash.class);

    ReceiveRequest receiveRequest = mock(ReceiveRequest.class);
    when(receiveRequest.getTransactionHash()).thenReturn(transactionHash);

    EncodedPayload payload = mock(EncodedPayload.class);

    EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    when(encryptedTransaction.getHash()).thenReturn(transactionHash);
    when(encryptedTransaction.getPayload()).thenReturn(payload);

    when(encryptedTransactionDAO.retrieveByHash(eq(transactionHash)))
        .thenReturn(Optional.of(encryptedTransaction));

    when(enclave.getPublicKeys()).thenReturn(Set.of(mock(PublicKey.class)));

    when(enclave.unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class)))
        .thenThrow(EncryptorException.class);

    try {
      transactionManager.receive(receiveRequest);
      failBecauseExceptionWasNotThrown(RecipientKeyNotFoundException.class);
    } catch (RecipientKeyNotFoundException ex) {
      verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
      verify(enclave).getPublicKeys();
      verify(enclave).unencryptTransaction(any(EncodedPayload.class), any(PublicKey.class));
    }
  }

  @Test
  public void storeRaw() {
    PublicKey sender = PublicKey.from("SENDER".getBytes());

    RawTransaction rawTransaction = mock(RawTransaction.class);
    when(rawTransaction.getEncryptedPayload()).thenReturn("CIPHERTEXT".getBytes());
    when(rawTransaction.getEncryptedKey()).thenReturn("SomeKey".getBytes());
    when(rawTransaction.getNonce()).thenReturn(new Nonce("nonce".getBytes()));
    when(rawTransaction.getFrom()).thenReturn(sender);

    when(enclave.encryptRawPayload(any(), any())).thenReturn(rawTransaction);

    byte[] payload = Base64.getEncoder().encode("PAYLOAD".getBytes());
    StoreRawRequest sendRequest = mock(StoreRawRequest.class);
    when(sendRequest.getSender()).thenReturn(sender);
    when(sendRequest.getPayload()).thenReturn(payload);

    MessageHash expectedHash = new MessageHash(mockDigest.digest("CIPHERTEXT".getBytes()));

    StoreRawResponse result = transactionManager.store(sendRequest);

    assertThat(result).isNotNull();
    assertThat(result.getHash().getHashBytes()).containsExactly(expectedHash.getHashBytes());

    verify(enclave).encryptRawPayload(eq(payload), eq(sender));
    verify(encryptedRawTransactionDAO)
        .save(
            argThat(
                et -> {
                  assertThat(et.getEncryptedKey()).containsExactly("SomeKey".getBytes());
                  assertThat(et.getEncryptedPayload()).containsExactly("CIPHERTEXT".getBytes());
                  assertThat(et.getHash()).isEqualTo(expectedHash);
                  assertThat(et.getNonce()).containsExactly("nonce".getBytes());
                  assertThat(et.getSender()).containsExactly(sender.getKeyBytes());
                  return true;
                }));
  }

  @Test
  public void constructWithLessArgs() {

    TransactionManager tm =
        new TransactionManagerImpl(
            enclave,
            encryptedTransactionDAO,
            encryptedRawTransactionDAO,
            resendManager,
            batchPayloadPublisher,
            privacyHelper,
            mockDigest);

    assertThat(tm).isNotNull();
  }

  @Test
  public void isSenderThrowsOnMissingTransaction() {

    MessageHash transactionHash = mock(MessageHash.class);
    when(transactionHash.getHashBytes()).thenReturn("DUMMY_TRANSACTION".getBytes());

    when(encryptedTransactionDAO.retrieveByHash(transactionHash)).thenReturn(Optional.empty());

    final Throwable throwable = catchThrowable(() -> transactionManager.isSender(transactionHash));

    assertThat(throwable)
        .isInstanceOf(TransactionNotFoundException.class)
        .hasMessage("Message with hash RFVNTVlfVFJBTlNBQ1RJT04= was not found");

    verify(encryptedTransactionDAO).retrieveByHash(transactionHash);
  }

  @Test
  public void isSenderReturnsFalseIfSenderNotFoundInPublicKeys() {
    final MessageHash transactionHash = mock(MessageHash.class);

    final EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);

    final EncodedPayload encodedPayload = mock(EncodedPayload.class);
    PublicKey sender = mock(PublicKey.class);
    when(encodedPayload.getSenderKey()).thenReturn(sender);

    when(encryptedTransaction.getPayload()).thenReturn(encodedPayload);

    when(encryptedTransactionDAO.retrieveByHash(transactionHash))
        .thenReturn(Optional.of(encryptedTransaction));

    when(enclave.getPublicKeys()).thenReturn(emptySet());

    final boolean isSender = transactionManager.isSender(transactionHash);

    assertThat(isSender).isFalse();

    verify(enclave).getPublicKeys();
    verify(encryptedTransactionDAO).retrieveByHash(transactionHash);
  }

  @Test
  public void isSenderReturnsTrueIfSender() {

    MessageHash transactionHash = mock(MessageHash.class);
    when(transactionHash.getHashBytes()).thenReturn("DUMMY_TRANSACTION".getBytes());

    final byte[] input = "SOMEDATA".getBytes();
    final EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);

    final PublicKey senderKey = mock(PublicKey.class);

    final EncodedPayload encodedPayload = mock(EncodedPayload.class);
    when(encodedPayload.getSenderKey()).thenReturn(senderKey);
    when(encryptedTransaction.getPayload()).thenReturn(encodedPayload);
    when(encryptedTransactionDAO.retrieveByHash(transactionHash))
        .thenReturn(Optional.of(encryptedTransaction));

    when(enclave.getPublicKeys()).thenReturn(Set.of(senderKey));

    final boolean isSender = transactionManager.isSender(transactionHash);

    assertThat(isSender).isTrue();

    verify(enclave).getPublicKeys();

    verify(encryptedTransactionDAO).retrieveByHash(transactionHash);
  }

  @Test
  public void getParticipantsThrowsOnMissingTransaction() {

    MessageHash transactionHash = mock(MessageHash.class);
    when(transactionHash.getHashBytes()).thenReturn("DUMMY_TRANSACTION".getBytes());

    when(encryptedTransactionDAO.retrieveByHash(any(MessageHash.class)))
        .thenReturn(Optional.empty());

    final Throwable throwable =
        catchThrowable(() -> transactionManager.getParticipants(transactionHash));

    assertThat(throwable)
        .isInstanceOf(TransactionNotFoundException.class)
        .hasMessage("Message with hash RFVNTVlfVFJBTlNBQ1RJT04= was not found");

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
  }

  @Test
  public void getParticipantsReturnsAllRecipients() {

    MessageHash transactionHash = mock(MessageHash.class);

    final PublicKey senderKey = mock(PublicKey.class);
    final PublicKey recipientKey = mock(PublicKey.class);

    final EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    final EncodedPayload encodedPayload = mock(EncodedPayload.class);
    when(encodedPayload.getRecipientKeys()).thenReturn(List.of(senderKey, recipientKey));
    when(encryptedTransaction.getPayload()).thenReturn(encodedPayload);

    when(encryptedTransactionDAO.retrieveByHash(transactionHash))
        .thenReturn(Optional.of(encryptedTransaction));

    final List<PublicKey> participants = transactionManager.getParticipants(transactionHash);

    assertThat(participants).containsExactlyInAnyOrder(senderKey, recipientKey);
    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
  }

  @Test
  public void getMandatoryRecipients() {

    MessageHash transactionHash = mock(MessageHash.class);

    final PublicKey senderKey = mock(PublicKey.class);
    final PublicKey recipientKey = mock(PublicKey.class);

    final EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    final EncodedPayload encodedPayload = mock(EncodedPayload.class);
    when(encodedPayload.getRecipientKeys()).thenReturn(List.of(senderKey, recipientKey));
    when(encodedPayload.getPrivacyMode()).thenReturn(PrivacyMode.MANDATORY_RECIPIENTS);
    when(encodedPayload.getMandatoryRecipients()).thenReturn(Set.of(recipientKey));

    when(encryptedTransaction.getPayload()).thenReturn(encodedPayload);

    when(encryptedTransactionDAO.retrieveByHash(transactionHash))
        .thenReturn(Optional.of(encryptedTransaction));

    final Set<PublicKey> participants = transactionManager.getMandatoryRecipients(transactionHash);

    assertThat(participants).containsExactly(recipientKey);

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
  }

  @Test
  public void getMandatoryRecipientsNotAvailable() {

    MessageHash transactionHash = mock(MessageHash.class);

    final PublicKey senderKey = mock(PublicKey.class);
    final PublicKey recipientKey = mock(PublicKey.class);

    final EncodedPayload encodedPayload = mock(EncodedPayload.class);
    when(encodedPayload.getRecipientKeys()).thenReturn(List.of(senderKey, recipientKey));
    when(encodedPayload.getPrivacyMode()).thenReturn(PrivacyMode.PARTY_PROTECTION);

    final EncryptedTransaction encryptedTransaction = mock(EncryptedTransaction.class);
    when(encryptedTransaction.getPayload()).thenReturn(encodedPayload);
    when(encryptedTransactionDAO.retrieveByHash(transactionHash))
        .thenReturn(Optional.of(encryptedTransaction));

    assertThatExceptionOfType(MandatoryRecipientsNotAvailableException.class)
        .isThrownBy(() -> transactionManager.getMandatoryRecipients(transactionHash))
        .withMessageContaining(
            "Operation invalid. Transaction found is not a mandatory recipients privacy type");

    verify(encryptedTransactionDAO).retrieveByHash(any(MessageHash.class));
  }

  @Test
  public void defaultPublicKey() {
    transactionManager.defaultPublicKey();
    verify(enclave).defaultPublicKey();
  }

  @Test
  public void upcheckReturnsTrue() {

    when(encryptedTransactionDAO.upcheck()).thenReturn(true);
    when(encryptedRawTransactionDAO.upcheck()).thenReturn(true);

    assertThat(transactionManager.upcheck()).isTrue();

    verify(encryptedRawTransactionDAO).upcheck();
    verify(encryptedTransactionDAO).upcheck();
  }

  @Test
  public void upcheckReturnsFalseIfEncryptedTransactionDBFail() {

    when(encryptedTransactionDAO.upcheck()).thenReturn(false);
    when(encryptedRawTransactionDAO.upcheck()).thenReturn(true);

    assertThat(transactionManager.upcheck()).isFalse();

    verify(encryptedRawTransactionDAO).upcheck();
    verify(encryptedTransactionDAO).upcheck();
  }

  @Test
  public void upcheckReturnsFalseIfEncryptedRawTransactionDBFail() {

    when(encryptedTransactionDAO.upcheck()).thenReturn(true);
    when(encryptedRawTransactionDAO.upcheck()).thenReturn(false);

    assertThat(transactionManager.upcheck()).isFalse();

    verify(encryptedRawTransactionDAO).upcheck();
  }

  @Test
  public void create() {
    TransactionManager expected = mock(TransactionManager.class);
    TransactionManager result;
    try (var mockedStaticServiceLoader = mockStatic(ServiceLoader.class)) {

      ServiceLoader<TransactionManager> serviceLoader = mock(ServiceLoader.class);
      when(serviceLoader.findFirst()).thenReturn(Optional.of(expected));

      mockedStaticServiceLoader
          .when(() -> ServiceLoader.load(TransactionManager.class))
          .thenReturn(serviceLoader);

      result = TransactionManager.create();

      verify(serviceLoader).findFirst();
      verifyNoMoreInteractions(serviceLoader);

      mockedStaticServiceLoader.verify(() -> ServiceLoader.load(TransactionManager.class));
      mockedStaticServiceLoader.verifyNoMoreInteractions();
    }

    assertThat(result).isSameAs(expected);
  }
}
