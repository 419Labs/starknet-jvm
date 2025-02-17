package com.swmansion.starknet.account

import com.swmansion.starknet.crypto.estimatedFeeToMaxFee
import com.swmansion.starknet.data.types.*
import com.swmansion.starknet.data.types.transactions.*
import com.swmansion.starknet.data.types.transactions.DeployAccountTransactionPayload
import com.swmansion.starknet.extensions.compose
import com.swmansion.starknet.provider.Provider
import com.swmansion.starknet.provider.Request
import com.swmansion.starknet.signer.Signer
import com.swmansion.starknet.signer.StarkCurveSigner
import java.math.BigInteger

/**
 * Standard account used in StarkNet.
 *
 * @param provider a provider used to interact with StarkNet
 * @param address the address of the account contract
 * @param signer a signer instance used to sign transactions
 */
class StandardAccount(
    override val address: Felt,
    private val signer: Signer,
    private val provider: Provider,
) : Account {
    private val version = Felt.ONE
    private val estimateVersion: BigInteger = BigInteger.valueOf(2).pow(128).add(version.value)

    /**
     * @param provider a provider used to interact with StarkNet
     * @param address the address of the account contract
     * @param privateKey a private key used to create a signer
     */
    constructor(address: Felt, privateKey: Felt, provider: Provider) : this(
        address,
        StarkCurveSigner(privateKey),
        provider,
    )

    override fun sign(calls: List<Call>, params: ExecutionParams, forFeeEstimate: Boolean): InvokeTransactionPayload {
        val calldata = callsToExecuteCalldata(calls)
        val signVersion = when (forFeeEstimate) {
            true -> Felt(estimateVersion)
            false -> version
        }
        val tx = TransactionFactory.makeInvokeTransaction(
            senderAddress = address,
            calldata = calldata,
            chainId = provider.chainId,
            nonce = params.nonce,
            maxFee = params.maxFee,
            version = signVersion,
        )

        val signedTransaction = tx.copy(signature = signer.signTransaction(tx))

        return signedTransaction.toPayload()
    }

    override fun signDeployAccount(
        classHash: Felt,
        calldata: Calldata,
        salt: Felt,
        maxFee: Felt,
        forFeeEstimate: Boolean,
    ): DeployAccountTransactionPayload {
        val signVersion = when (forFeeEstimate) {
            true -> Felt(estimateVersion)
            false -> version
        }
        val tx = TransactionFactory.makeDeployAccountTransaction(
            classHash = classHash,
            contractAddress = address,
            salt = salt,
            calldata = calldata,
            chainId = provider.chainId,
            maxFee = maxFee,
            version = signVersion,
        )
        val signedTransaction = tx.copy(signature = signer.signTransaction(tx))

        return signedTransaction.toPayload()
    }

    override fun signDeclare(
        contractDefinition: ContractDefinition,
        classHash: Felt,
        params: ExecutionParams,
        forFeeEstimate: Boolean,
    ): DeclareTransactionPayload {
        val signVersion = when (forFeeEstimate) {
            true -> Felt(estimateVersion)
            false -> version
        }
        val tx = TransactionFactory.makeDeclareTransaction(
            contractDefinition = contractDefinition,
            classHash = classHash,
            senderAddress = address,
            chainId = provider.chainId,
            nonce = params.nonce,
            maxFee = params.maxFee,
            version = signVersion,
        )
        val signedTransaction = tx.copy(signature = signer.signTransaction(tx))

        return signedTransaction.toPayload()
    }

    override fun execute(calls: List<Call>, maxFee: Felt): Request<InvokeFunctionResponse> {
        return getNonce().compose { nonce ->
            val signParams = ExecutionParams(nonce = nonce, maxFee = maxFee)
            val payload = sign(calls, signParams)

            return@compose provider.invokeFunction(payload)
        }
    }

    override fun execute(calls: List<Call>): Request<InvokeFunctionResponse> {
        return estimateFee(calls).compose { estimateFee ->
            val maxFee = estimatedFeeToMaxFee(estimateFee.overallFee)
            execute(calls, maxFee)
        }
    }

    override fun getNonce(): Request<Felt> = provider.getNonce(address, BlockTag.PENDING)

    override fun estimateFee(calls: List<Call>): Request<EstimateFeeResponse> {
        return getNonce().compose { buildEstimateFeeRequest(calls, it) }
    }

    private fun buildEstimateFeeRequest(calls: List<Call>, nonce: Felt): Request<EstimateFeeResponse> {
        val executionParams = ExecutionParams(nonce = nonce, maxFee = Felt.ZERO)
        val payload = sign(calls, executionParams, true)

        val signedTransaction = TransactionFactory.makeInvokeTransaction(
            senderAddress = payload.senderAddress,
            calldata = payload.calldata,
            chainId = provider.chainId,
            nonce = nonce,
            maxFee = payload.maxFee,
            signature = payload.signature,
            version = payload.version,
        )

        return provider.getEstimateFee(signedTransaction.toPayload(), BlockTag.LATEST)
    }
}
