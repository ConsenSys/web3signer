/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.web3signer.dsl.utils;

import static java.util.Collections.emptyList;
import static tech.pegasys.web3signer.core.util.DepositSigningRootUtil.computeDomain;

import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.bytes.Bytes20;
import tech.pegasys.teku.infrastructure.bytes.Bytes4;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.constants.Domain;
import tech.pegasys.teku.spec.datastructures.operations.versions.altair.ContributionAndProof;
import tech.pegasys.teku.spec.datastructures.operations.versions.altair.SyncAggregatorSelectionData;
import tech.pegasys.teku.spec.datastructures.operations.versions.altair.SyncCommitteeContribution;
import tech.pegasys.teku.spec.logic.common.util.SyncCommitteeUtil;
import tech.pegasys.teku.spec.signatures.SigningRootUtil;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.web3signer.core.service.http.ArtifactType;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.AggregateAndProofV2;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.AggregationSlot;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.DepositMessage;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.Eth2SigningRequestBody;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.ForkInfo;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.RandaoReveal;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.SyncCommitteeMessage;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.ValidatorRegistration;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.VersionedRequest;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.AggregateAndProof;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.Attestation;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.AttestationData;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.BLSPubKey;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.BLSSignature;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.BeaconBlock;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.BeaconBlockBody;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.Checkpoint;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.Eth1Data;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.Fork;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.KZGCommitment;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.VoluntaryExit;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.electra.ExecutionRequests;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.gloas.BuilderRequestAuth;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.gloas.ExecutionPayloadBid;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.gloas.ExecutionPayloadEnvelope;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.gloas.ExecutionPayloadGloas;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.gloas.PayloadAttestationData;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.gloas.ProposerPreferences;
import tech.pegasys.web3signer.core.util.DepositSigningRootUtil;

import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

public class Eth2RequestUtils {
  // mainnet GVR: must match the GVR web3signer sets at startup for the default --network mainnet
  public static final String GENESIS_VALIDATORS_ROOT =
      "0x4b363db94e286120d76eb905340fdd4e54bfe9f06bf33ff6cf5ad27f511bfe95";

  private static final String PUBLIC_KEY =
      "0x8f82597c919c056571a05dfe83e6a7d32acf9ad8931be04d11384e95468cd68b40129864ae12745f774654bbac09b057";

  private static final UInt64 SLOT = UInt64.ZERO;
  private static final Spec SPEC = TestSpecFactory.createMinimalPhase0();

  private static final SigningRootUtil SIGNING_ROOT_UTIL = new SigningRootUtil(SPEC);

  // Altair Spec
  private static final Spec ALTAIR_SPEC = TestSpecFactory.createMinimalAltair();
  private static final DataStructureUtil DATA_STRUCTURE_UTIL = new DataStructureUtil(ALTAIR_SPEC);
  private static final Bytes32 BEACON_BLOCK_ROOT = DATA_STRUCTURE_UTIL.randomBytes32();
  private static final tech.pegasys.teku.bls.BLSSignature AGGREGATOR_SIGNATURE =
      tech.pegasys.teku.bls.BLSSignature.fromBytesCompressed(
          Bytes.fromHexString(
              "0x8f5c34de9e22ceaa7e8d165fc0553b32f02188539e89e2cc91e2eb9077645986550d872ee3403204ae5d554eae3cac12124e18d2324bccc814775316aaef352abc0450812b3ca9fde96ecafa911b3b8bfddca8db4027f08e29c22a9c370ad933"));
  private static final SyncCommitteeUtil SYNC_COMMITTEE_UTIL =
      ALTAIR_SPEC.getSyncCommitteeUtilRequired(SLOT);
  private static final SyncCommitteeContribution CONTRIBUTION =
      DATA_STRUCTURE_UTIL.randomSyncCommitteeContribution(SLOT, BEACON_BLOCK_ROOT);
  private static final ContributionAndProof CONTRIBUTION_AND_PROOF =
      SYNC_COMMITTEE_UTIL.createContributionAndProof(
          UInt64.valueOf(11), CONTRIBUTION, AGGREGATOR_SIGNATURE);

  private static final Eth2BlockSigningRequestUtil ALTAIR_BLOCK_UTIL =
      new Eth2BlockSigningRequestUtil(SpecMilestone.ALTAIR);

  // Gloas Spec
  private static final Spec GLOAS_SPEC = TestSpecFactory.createMinimalGloas();
  private static final DataStructureUtil GLOAS_DATA_STRUCTURE_UTIL =
      new DataStructureUtil(GLOAS_SPEC);
  private static final SigningRootUtil GLOAS_SIGNING_ROOT_UTIL = new SigningRootUtil(GLOAS_SPEC);

  public static Eth2SigningRequestBody createCannedRequest(final ArtifactType artifactType) {
    return switch (artifactType) {
      case DEPOSIT -> createDepositRequest();

      case VOLUNTARY_EXIT -> createVoluntaryExit();

      case RANDAO_REVEAL -> createRandaoReveal();

      case BLOCK -> createBlockRequest();

      case BLOCK_V2 -> ALTAIR_BLOCK_UTIL.createBlockV2Request();

      case ATTESTATION -> createAttestationRequest();

      case AGGREGATION_SLOT -> createAggregationSlot();

      case AGGREGATE_AND_PROOF -> createAggregateAndProof(true);

      case AGGREGATE_AND_PROOF_V2 -> createAggregateAndProof(false);

      case SYNC_COMMITTEE_MESSAGE -> createSyncCommitteeMessageRequest();

      case SYNC_COMMITTEE_SELECTION_PROOF -> createSyncCommitteeSelectionProofRequest();

      case SYNC_COMMITTEE_CONTRIBUTION_AND_PROOF ->
          createSyncCommitteeContributionAndProofRequest();

      case VALIDATOR_REGISTRATION -> createValidatorRegistrationRequest();

      case EXECUTION_PAYLOAD_BID -> createExecutionPayloadBidRequest();

      case EXECUTION_PAYLOAD_ENVELOPE -> createExecutionPayloadEnvelopeRequest();

      case PAYLOAD_ATTESTATION_MESSAGE -> createPayloadAttestationMessageRequest();

      case PROPOSER_PREFERENCES -> createProposerPreferencesRequest();

      case BUILDER_REQUEST_AUTH -> createBuilderRequestAuthRequest();
    };
  }

  private static Eth2SigningRequestBody createAggregateAndProof(final boolean isLegacy) {
    final ForkInfo forkInfo = forkInfo();
    final Bytes sszBytes = Bytes.of(0, 0, 1, 1);
    final AttestationData attestationData =
        new AttestationData(
            UInt64.ZERO,
            UInt64.ZERO,
            Bytes32.fromHexString(
                "0x100814c335d0ced5014cfa9d2e375e6d9b4e197381f8ce8af0473200fdc917fd"),
            new Checkpoint(UInt64.ZERO, Bytes32.ZERO),
            new Checkpoint(
                UInt64.ZERO,
                Bytes32.fromHexString(
                    "0x100814c335d0ced5014cfa9d2e375e6d9b4e197381f8ce8af0473200fdc917fd")));
    final Attestation attestation =
        new Attestation(
            sszBytes,
            attestationData,
            null, // committee_bits in pre-Electra Attestation must be null
            BLSSignature.fromHexString(
                "0xa627242e4a5853708f4ebf923960fb8192f93f2233cd347e05239d86dd9fb66b721ceec1baeae6647f498c9126074f1101a87854d674b6eebc220fd8c3d8405bdfd8e286b707975d9e00a56ec6cbbf762f23607d490f0bbb16c3e0e483d51875"));
    final BLSSignature selectionProof =
        BLSSignature.fromHexString(
            "0xa63f73a03f1f42b1fd0a988b614d511eb346d0a91c809694ef76df5ae021f0f144d64e612d735bc8820950cf6f7f84cd0ae194bfe3d4242fe79688f83462e3f69d9d33de71aab0721b7dab9d6960875e5fdfd26b171a75fb51af822043820c47");
    final AggregateAndProof aggregateAndProof =
        new AggregateAndProof(UInt64.ONE, attestation, selectionProof);
    final Bytes signingRoot =
        SIGNING_ROOT_UTIL.signingRootForSignAggregateAndProof(
            aggregateAndProof.asInternalAggregateAndProof(SPEC), forkInfo.asInternalForkInfo());
    return Eth2SigningRequestBodyBuilder.anEth2SigningRequestBody()
        .withType(getAggregateAndProofType(isLegacy))
        .withSigningRoot(signingRoot)
        .withForkInfo(forkInfo)
        .withAggregateAndProofV2(
            new AggregateAndProofV2(isLegacy ? null : SpecMilestone.PHASE0, aggregateAndProof))
        .build();
  }

  @SuppressWarnings("deprecation")
  private static ArtifactType getAggregateAndProofType(final boolean isLegacy) {
    return isLegacy ? ArtifactType.AGGREGATE_AND_PROOF : ArtifactType.AGGREGATE_AND_PROOF_V2;
  }

  private static Eth2SigningRequestBody createAggregationSlot() {
    final ForkInfo forkInfo = forkInfo();
    final AggregationSlot aggregationSlot = new AggregationSlot(UInt64.valueOf(119));
    final Bytes signingRoot =
        SIGNING_ROOT_UTIL.signingRootForSignAggregationSlot(
            aggregationSlot.getSlot(), forkInfo.asInternalForkInfo());

    return Eth2SigningRequestBodyBuilder.anEth2SigningRequestBody()
        .withType(ArtifactType.AGGREGATION_SLOT)
        .withSigningRoot(signingRoot)
        .withForkInfo(forkInfo)
        .withAggregationSlot(aggregationSlot)
        .build();
  }

  private static Eth2SigningRequestBody createAttestationRequest() {
    return createAttestationRequest(0, 0, UInt64.ZERO);
  }

  private static Eth2SigningRequestBody createRandaoReveal() {
    final ForkInfo forkInfo = forkInfo();
    final RandaoReveal randaoReveal = new RandaoReveal(UInt64.valueOf(3));
    final Bytes signingRoot =
        SIGNING_ROOT_UTIL.signingRootForRandaoReveal(
            randaoReveal.getEpoch(), forkInfo.asInternalForkInfo());
    return Eth2SigningRequestBodyBuilder.anEth2SigningRequestBody()
        .withType(ArtifactType.RANDAO_REVEAL)
        .withSigningRoot(signingRoot)
        .withForkInfo(forkInfo)
        .withRandaoReveal(randaoReveal)
        .build();
  }

  private static Eth2SigningRequestBody createVoluntaryExit() {
    final ForkInfo forkInfo = forkInfo();
    final VoluntaryExit voluntaryExit = new VoluntaryExit(UInt64.valueOf(119), UInt64.ZERO);
    final Bytes signingRoot =
        SIGNING_ROOT_UTIL.signingRootForSignVoluntaryExit(
            voluntaryExit.asInternalVoluntaryExit(), forkInfo.asInternalForkInfo());
    return Eth2SigningRequestBodyBuilder.anEth2SigningRequestBody()
        .withType(ArtifactType.VOLUNTARY_EXIT)
        .withSigningRoot(signingRoot)
        .withForkInfo(forkInfo)
        .withVoluntaryExit(voluntaryExit)
        .build();
  }

  private static Eth2SigningRequestBody createDepositRequest() {
    final Bytes4 genesisForkVersion = Bytes4.fromHexString("0x00000001");
    final DepositMessage depositMessage =
        new DepositMessage(
            BLSPubKey.fromHexString(PUBLIC_KEY),
            Bytes32.random(new Random(2)),
            UInt64.valueOf(32),
            genesisForkVersion);
    final Bytes32 depositDomain = computeDomain(Domain.DEPOSIT, genesisForkVersion, Bytes32.ZERO);
    final Bytes signingRoot =
        DepositSigningRootUtil.computeSigningRoot(
            depositMessage.asInternalDepositMessage(), depositDomain);
    return Eth2SigningRequestBodyBuilder.anEth2SigningRequestBody()
        .withType(ArtifactType.DEPOSIT)
        .withSigningRoot(signingRoot)
        .withDeposit(depositMessage)
        .build();
  }

  public static Eth2SigningRequestBody createAttestationRequest(
      final int sourceEpoch, final int targetEpoch, final UInt64 slot) {
    final ForkInfo forkInfo = forkInfo();
    final AttestationData attestationData =
        new AttestationData(
            UInt64.valueOf(32),
            slot,
            Bytes32.fromHexString(
                "0xb2eedb01adbd02c828d5eec09b4c70cbba12ffffba525ebf48aca33028e8ad89"),
            new Checkpoint(UInt64.valueOf(sourceEpoch), Bytes32.ZERO),
            new Checkpoint(
                UInt64.valueOf(targetEpoch),
                Bytes32.fromHexString(
                    "0xb2eedb01adbd02c828d5eec09b4c70cbba12ffffba525ebf48aca33028e8ad89")));
    final Bytes signingRoot =
        SIGNING_ROOT_UTIL.signingRootForSignAttestationData(
            attestationData.asInternalAttestationData(), forkInfo.asInternalForkInfo());
    return Eth2SigningRequestBodyBuilder.anEth2SigningRequestBody()
        .withType(ArtifactType.ATTESTATION)
        .withSigningRoot(signingRoot)
        .withForkInfo(forkInfo)
        .withAttestation(attestationData)
        .build();
  }

  public static Eth2SigningRequestBody createBlockRequest() {
    return createBlockRequest(UInt64.ZERO, Bytes32.fromHexString("0x"));
  }

  public static Eth2SigningRequestBody createBlockRequest(
      final UInt64 slot, final Bytes32 stateRoot) {

    final ForkInfo forkInfo = forkInfo();
    final tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.BeaconBlock block =
        new BeaconBlock(
            slot,
            UInt64.valueOf(5),
            Bytes32.fromHexString(
                "0xb2eedb01adbd02c828d5eec09b4c70cbba12ffffba525ebf48aca33028e8ad89"),
            stateRoot,
            new BeaconBlockBody(
                BLSSignature.fromHexString(
                    "0xa686652aed2617da83adebb8a0eceea24bb0d2ccec9cd691a902087f90db16aa5c7b03172a35e874e07e3b60c5b2435c0586b72b08dfe5aee0ed6e5a2922b956aa88ad0235b36dfaa4d2255dfeb7bed60578d982061a72c7549becab19b3c12f"),
                new Eth1Data(
                    Bytes32.fromHexString(
                        "0x6a0f9d6cb0868daa22c365563bb113b05f7568ef9ee65fdfeb49a319eaf708cf"),
                    UInt64.valueOf(8),
                    Bytes32.fromHexString(
                        "0x4242424242424242424242424242424242424242424242424242424242424242")),
                Bytes32.fromHexString(
                    "0x74656b752f76302e31322e31302d6465762d6338316361363235000000000000"),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList()));
    final Bytes signingRoot =
        SIGNING_ROOT_UTIL.signingRootForSignBlock(
            block.asInternalBeaconBlock(SPEC), forkInfo.asInternalForkInfo());
    return Eth2SigningRequestBodyBuilder.anEth2SigningRequestBody()
        .withType(ArtifactType.BLOCK)
        .withSigningRoot(signingRoot)
        .withForkInfo(forkInfo)
        .withBlock(block)
        .build();
  }

  public static ForkInfo forkInfo() {
    final Fork fork =
        new Fork(
            Bytes4.fromHexString("0x00000001"),
            Bytes4.fromHexString("0x00000001"),
            UInt64.valueOf(1));
    final Bytes32 genesisValidatorsRoot = Bytes32.fromHexString(GENESIS_VALIDATORS_ROOT);
    return new ForkInfo(fork, genesisValidatorsRoot);
  }

  public static ForkInfo forkInfo(final long epoch) {
    final Fork fork =
        new Fork(
            Bytes4.fromHexString("0x00000001"),
            Bytes4.fromHexString("0x00000001"),
            UInt64.valueOf(epoch));
    final Bytes32 genesisValidatorsRoot = Bytes32.fromHexString(GENESIS_VALIDATORS_ROOT);
    return new ForkInfo(fork, genesisValidatorsRoot);
  }

  private static Eth2SigningRequestBody createSyncCommitteeMessageRequest() {
    final ForkInfo forkInfo = forkInfo();
    final Bytes signingRoot;
    try {
      signingRoot =
          signingRootFromSyncCommitteeUtils(
                  SLOT,
                  utils ->
                      utils.getSyncCommitteeMessageSigningRoot(
                          BEACON_BLOCK_ROOT,
                          ALTAIR_SPEC.computeEpochAtSlot(SLOT),
                          forkInfo.asInternalForkInfo()))
              .get();
    } catch (final InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }

    final SyncCommitteeMessage syncCommitteeMessage =
        new SyncCommitteeMessage(BEACON_BLOCK_ROOT, SLOT);
    return Eth2SigningRequestBodyBuilder.anEth2SigningRequestBody()
        .withType(ArtifactType.SYNC_COMMITTEE_MESSAGE)
        .withSigningRoot(signingRoot)
        .withForkInfo(forkInfo)
        .withSyncCommitteeMessage(syncCommitteeMessage)
        .build();
  }

  private static Eth2SigningRequestBody createSyncCommitteeSelectionProofRequest() {
    final ForkInfo forkInfo = forkInfo();
    final UInt64 subcommitteeIndex = UInt64.ZERO;
    final SyncAggregatorSelectionData selectionData =
        SYNC_COMMITTEE_UTIL.createSyncAggregatorSelectionData(SLOT, subcommitteeIndex);

    final Bytes signingRoot;
    try {
      signingRoot =
          signingRootFromSyncCommitteeUtils(
                  SLOT,
                  utils ->
                      utils.getSyncAggregatorSelectionDataSigningRoot(
                          selectionData, forkInfo.asInternalForkInfo()))
              .get();
    } catch (final InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }

    return Eth2SigningRequestBodyBuilder.anEth2SigningRequestBody()
        .withType(ArtifactType.SYNC_COMMITTEE_SELECTION_PROOF)
        .withSigningRoot(signingRoot)
        .withForkInfo(forkInfo)
        .withSyncAggregatorSelectionData(getSyncAggregatorSelectionData(SLOT, subcommitteeIndex))
        .build();
  }

  private static tech.pegasys.web3signer.core.service.http.handlers.signing.eth2
          .SyncAggregatorSelectionData
      getSyncAggregatorSelectionData(final UInt64 slot, final UInt64 subcommitteeIndex) {
    return new tech.pegasys.web3signer.core.service.http.handlers.signing.eth2
        .SyncAggregatorSelectionData(slot, subcommitteeIndex);
  }

  private static Eth2SigningRequestBody createSyncCommitteeContributionAndProofRequest() {
    final ForkInfo forkInfo = forkInfo();
    final Bytes signingRoot;
    try {
      signingRoot =
          signingRootFromSyncCommitteeUtils(
                  SLOT,
                  utils ->
                      utils.getContributionAndProofSigningRoot(
                          CONTRIBUTION_AND_PROOF, forkInfo.asInternalForkInfo()))
              .get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }

    return Eth2SigningRequestBodyBuilder.anEth2SigningRequestBody()
        .withType(ArtifactType.SYNC_COMMITTEE_CONTRIBUTION_AND_PROOF)
        .withSigningRoot(signingRoot)
        .withForkInfo(forkInfo)
        .withContributionAndProof(getContributionAndProof())
        .build();
  }

  private static Eth2SigningRequestBody createValidatorRegistrationRequest() {
    final ValidatorRegistration validatorRegistration =
        new ValidatorRegistration(
            DATA_STRUCTURE_UTIL.randomBytes20(),
            DATA_STRUCTURE_UTIL.randomUInt64(),
            DATA_STRUCTURE_UTIL.randomUInt64(),
            BLSPubKey.fromHexString(PUBLIC_KEY));
    final Bytes signingRoot =
        SIGNING_ROOT_UTIL.signingRootForValidatorRegistration(
            validatorRegistration.asInternalValidatorRegistration());
    return Eth2SigningRequestBodyBuilder.anEth2SigningRequestBody()
        .withType(ArtifactType.VALIDATOR_REGISTRATION)
        .withSigningRoot(signingRoot)
        .withValidatorRegistration(validatorRegistration)
        .build();
  }

  public static ForkInfo gloasForkInfo() {
    final tech.pegasys.teku.spec.datastructures.state.Fork internalFork =
        GLOAS_SPEC.getForkSchedule().getFork(UInt64.ZERO);
    final Fork fork =
        new Fork(
            internalFork.getPreviousVersion(),
            internalFork.getCurrentVersion(),
            internalFork.getEpoch());
    return new ForkInfo(fork, Bytes32.fromHexString(GENESIS_VALIDATORS_ROOT));
  }

  private static Eth2SigningRequestBody createExecutionPayloadBidRequest() {
    final ForkInfo forkInfo = gloasForkInfo();
    final tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadBid randomBid =
        GLOAS_DATA_STRUCTURE_UTIL.randomExecutionPayloadBid();
    final ExecutionPayloadBid bid =
        new ExecutionPayloadBid(
            randomBid.getParentBlockHash(),
            randomBid.getParentBlockRoot(),
            randomBid.getBlockHash(),
            randomBid.getPrevRandao(),
            new Bytes20(randomBid.getFeeRecipient().getWrappedBytes()),
            randomBid.getGasLimit(),
            randomBid.getBuilderIndex(),
            randomBid.getSlot(),
            randomBid.getValue(),
            randomBid.getExecutionPayment(),
            randomBid.getBlobKzgCommitments().stream()
                .map(c -> new KZGCommitment(c.getKZGCommitment()))
                .toList(),
            randomBid.getExecutionRequestsRoot());
    final Bytes signingRoot =
        GLOAS_SIGNING_ROOT_UTIL.signingRootForSignExecutionPayloadBid(
            randomBid, forkInfo.asInternalForkInfo());
    return Eth2SigningRequestBodyBuilder.anEth2SigningRequestBody()
        .withType(ArtifactType.EXECUTION_PAYLOAD_BID)
        .withSigningRoot(signingRoot)
        .withForkInfo(forkInfo)
        .withExecutionPayloadBid(new VersionedRequest<>(SpecMilestone.GLOAS, bid))
        .build();
  }

  private static Eth2SigningRequestBody createExecutionPayloadEnvelopeRequest() {
    final ForkInfo forkInfo = gloasForkInfo();
    final tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelope
        randomEnvelope =
            GLOAS_DATA_STRUCTURE_UTIL.randomExecutionPayloadEnvelope(UInt64.valueOf(7));
    final ExecutionPayloadEnvelope envelope =
        new ExecutionPayloadEnvelope(
            new ExecutionPayloadGloas(randomEnvelope.getPayload()),
            new ExecutionRequests(randomEnvelope.getExecutionRequests()),
            randomEnvelope.getBuilderIndex(),
            randomEnvelope.getBeaconBlockRoot(),
            randomEnvelope.getParentBeaconBlockRoot());
    final Bytes signingRoot =
        GLOAS_SIGNING_ROOT_UTIL.signingRootForSignExecutionPayloadEnvelope(
            randomEnvelope, forkInfo.asInternalForkInfo());
    return Eth2SigningRequestBodyBuilder.anEth2SigningRequestBody()
        .withType(ArtifactType.EXECUTION_PAYLOAD_ENVELOPE)
        .withSigningRoot(signingRoot)
        .withForkInfo(forkInfo)
        .withExecutionPayloadEnvelope(new VersionedRequest<>(SpecMilestone.GLOAS, envelope))
        .build();
  }

  private static Eth2SigningRequestBody createPayloadAttestationMessageRequest() {
    final ForkInfo forkInfo = gloasForkInfo();
    final tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.PayloadAttestationData
        randomData = GLOAS_DATA_STRUCTURE_UTIL.randomPayloadAttestationData(UInt64.valueOf(7));
    final PayloadAttestationData payloadAttestationData =
        new PayloadAttestationData(
            randomData.getBeaconBlockRoot(),
            randomData.getSlot(),
            randomData.isPayloadPresent(),
            randomData.isBlobDataAvailable());
    final Bytes signingRoot =
        GLOAS_SIGNING_ROOT_UTIL.signingRootForSignPayloadAttestationData(
            randomData, forkInfo.asInternalForkInfo());
    return Eth2SigningRequestBodyBuilder.anEth2SigningRequestBody()
        .withType(ArtifactType.PAYLOAD_ATTESTATION_MESSAGE)
        .withSigningRoot(signingRoot)
        .withForkInfo(forkInfo)
        .withPayloadAttestationMessage(
            new VersionedRequest<>(SpecMilestone.GLOAS, payloadAttestationData))
        .build();
  }

  private static Eth2SigningRequestBody createProposerPreferencesRequest() {
    final ForkInfo forkInfo = gloasForkInfo();
    final tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ProposerPreferences
        randomPreferences = GLOAS_DATA_STRUCTURE_UTIL.randomProposerPreferences();
    final ProposerPreferences proposerPreferences =
        new ProposerPreferences(
            randomPreferences.getDependentRoot(),
            randomPreferences.getProposalSlot(),
            randomPreferences.getValidatorIndex(),
            randomPreferences.getFeeRecipient(),
            randomPreferences.getTargetGasLimit());
    final Bytes signingRoot =
        GLOAS_SIGNING_ROOT_UTIL.signingRootForSignProposerPreferences(
            randomPreferences, forkInfo.asInternalForkInfo());
    return Eth2SigningRequestBodyBuilder.anEth2SigningRequestBody()
        .withType(ArtifactType.PROPOSER_PREFERENCES)
        .withSigningRoot(signingRoot)
        .withForkInfo(forkInfo)
        .withProposerPreferences(new VersionedRequest<>(SpecMilestone.GLOAS, proposerPreferences))
        .build();
  }

  private static Eth2SigningRequestBody createBuilderRequestAuthRequest() {
    final tech.pegasys.teku.spec.datastructures.builder.versions.gloas.BuilderRequestAuth
        randomRequestAuth = GLOAS_DATA_STRUCTURE_UTIL.randomBuilderRequestAuth();
    final BuilderRequestAuth builderRequestAuth =
        new BuilderRequestAuth(randomRequestAuth.getData().getBytes(), randomRequestAuth.getSlot());
    final Bytes signingRoot =
        GLOAS_SIGNING_ROOT_UTIL.signingRootForSignBuilderRequestAuth(randomRequestAuth);
    return Eth2SigningRequestBodyBuilder.anEth2SigningRequestBody()
        .withType(ArtifactType.BUILDER_REQUEST_AUTH)
        .withSigningRoot(signingRoot)
        .withBuilderRequestAuth(new VersionedRequest<>(SpecMilestone.GLOAS, builderRequestAuth))
        .build();
  }

  private static tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.altair
          .ContributionAndProof
      getContributionAndProof() {
    return new tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.altair
        .ContributionAndProof(
        CONTRIBUTION_AND_PROOF.getAggregatorIndex(),
        new BLSSignature(CONTRIBUTION_AND_PROOF.getSelectionProof()),
        new tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.altair
            .SyncCommitteeContribution(CONTRIBUTION_AND_PROOF.getContribution()));
  }

  private static SafeFuture<Bytes> signingRootFromSyncCommitteeUtils(
      final UInt64 slot, final Function<SyncCommitteeUtil, Bytes> createSigningRoot) {
    return SafeFuture.of(
        () -> createSigningRoot.apply(ALTAIR_SPEC.getSyncCommitteeUtilRequired(slot)));
  }
}
