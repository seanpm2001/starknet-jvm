package starknet.utils

import com.swmansion.starknet.data.types.Felt
import com.swmansion.starknet.service.http.HttpService
import com.swmansion.starknet.service.http.OkHttpService
import kotlinx.serialization.json.*
import starknet.utils.data.*
import starknet.utils.data.serializers.AccountDetailsSerializer
import starknet.utils.data.serializers.SnCastResponsePolymorphicSerializer
import java.io.File
import java.lang.IllegalArgumentException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.readText

class DevnetClient(
    val host: String = "0.0.0.0",
    val port: Int = 5000,
    val seed: Int = 1053545547,
    private val httpService: HttpService = OkHttpService(),
    private val accountDirectory: Path = Paths.get("src/test/resources/account"),
    private val contractsDirectory: Path = Paths.get("src/test/resources/contracts"),
) : AutoCloseable {

    private val baseUrl: String = "http://$host:$port"
    val rpcUrl: String = "$baseUrl/rpc"
    val mintUrl: String = "$baseUrl/mint"

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var devnetPath: Path
    private lateinit var devnetProcess: Process
    private var isDevnetRunning = false

    private val accountFilePath = accountDirectory.resolve("starknet_open_zeppelin_accounts.json")
    private val scarbTomlPath = contractsDirectory.resolve("Scarb.toml")

    lateinit var defaultAccountDetails: AccountDetails

    companion object {
        // Source: https://github.com/0xSpaceShard/starknet-devnet-rs/blob/323f907bc3e3e4dc66b403ec6f8b58744e8d6f9a/crates/starknet/src/constants.rs
        val accountContractClassHash = Felt.fromHex("0x4d07e40e93398ed3c76981e72dd1fd22557a78ce36c0515f679e27f0bb5bc5f")
        val erc20ContractClassHash = Felt.fromHex("0x6a22bf63c7bc07effa39a25dfbd21523d211db0100a0afd054d172b81840eaf")
        val erc20ContractAddress = Felt.fromHex("0x49d36570d4e46f48e99674bd3fcc84644ddd6b96f7c741b1562b82f9e004dc7")
        val udcContractClassHash = Felt.fromHex("0x7b3e05f48f0c69e4a65ce5e076a66271a527aff2c34ce1083ec6e1526997a69")
        val udcContractAddress = Felt.fromHex("0x41a78e741e5af2fec34b695679bc6891742439f7afb8484ecd7766661ad02bf")

        // For seed 1053545547
        val predeployedAccount1 = AccountDetails(
            privateKey = Felt.fromHex("0xa2ed22bb0cb0b49c69f6d6a8d24bc5ea"),
            publicKey = Felt.fromHex("0x198e98e771ebb5da7f4f05658a80a3d6be2213dc5096d055cbbefa62901ab06"),
            address = Felt.fromHex("0x1323cacbc02b4aaed9bb6b24d121fb712d8946376040990f2f2fa0dcf17bb5b"),
            salt = Felt(20),
        )
        val predeployedAccount2 = AccountDetails(
            privateKey = Felt.fromHex("0xc1c7db92d22ef773de96f8bde8e56c85"),
            publicKey = Felt.fromHex("0x26df62f8e61920575f9c9391ed5f08397cfcfd2ade02d47781a4a8836c091fd"),
            address = Felt.fromHex("0x34864aab9f693157f88f2213ffdaa7303a46bbea92b702416a648c3d0e42f35"),
            salt = Felt(20),
        )
    }
    fun start() {
        if (isDevnetRunning) {
            throw DevnetSetupFailedException("Devnet is already running")
        }
        devnetPath = Paths.get(System.getenv("DEVNET_PATH")) ?: throw DevnetSetupFailedException(
            "DEVNET_PATH environment variable is not set. Make sure you have devnet installed https://github.com/0xSpaceShard/starknet-devnet-rs and DEVNET_PATH points to a devnet binary.",
        )

        // This kills any zombie devnet processes left over from previous test runs, if any.
        ProcessBuilder(
            "pkill",
            "-f",
            "starknet-devnet.*$port.*$seed",
        ).start().waitFor()

        val devnetProcessBuilder = ProcessBuilder(
            devnetPath.absolutePathString(),
            "--host",
            host,
            "--port",
            port.toString(),
            "--seed",
            seed.toString(),
        )
        devnetProcess = devnetProcessBuilder.start()
        devnetProcess.waitFor(3, TimeUnit.SECONDS)

        if (!devnetProcess.isAlive) {
            throw DevnetSetupFailedException("Could not start devnet process")
        }
        isDevnetRunning = true

        if (accountDirectory.exists()) {
            accountDirectory.toFile().walkTopDown().forEach { it.delete() }
        }

        defaultAccountDetails = createDeployAccount("__default__").details
    }

    override fun close() {
        if (!isDevnetRunning) {
            return
        }
        devnetProcess.destroyForcibly()

        // Wait for the process to be destroyed
        devnetProcess.waitFor()
        isDevnetRunning = false
    }

    fun prefundAccount(accountAddress: Felt) {
        val payload = HttpService.Payload(
            url = mintUrl,
            body =
            """
            {
              "address": "${accountAddress.hexString()}",
              "amount": 500000000000000000000000000000
            }
            """.trimIndent(),
            method = "POST",
            params = emptyList(),
        )
        val response = httpService.send(payload)
        if (!response.isSuccessful) {
            throw DevnetSetupFailedException("Prefunding account failed")
        }
    }

    fun createAccount(
        name: String,
        classHash: Felt = accountContractClassHash,
        salt: Felt? = null,
    ): CreateAccountResult {
        val params = mutableListOf(
            "create",
            "--name",
            name,
            "--class-hash",
            classHash.hexString(),
        )
        salt?.let {
            params.add("--salt")
            params.add(salt.hexString())
        }

        val response = runSnCast(
            command = "account",
            args = params,
        ) as AccountCreateSnCastResponse

        return CreateAccountResult(
            details = readAccountDetails(name),
            maxFee = response.maxFee,
        )
    }

    fun deployAccount(
        name: String,
        classHash: Felt = accountContractClassHash,
        maxFee: Felt = Felt(1000000000000000),
    ): DeployAccountResult {
        val params = listOf(
            "deploy",
            "--name",
            name,
            "--max-fee",
            maxFee.hexString(),
            "--class-hash",
            classHash.hexString(),
        )
        val response = runSnCast(
            command = "account",
            args = params,
        ) as AccountDeploySnCastResponse

        return DeployAccountResult(
            details = readAccountDetails(name),
            transactionHash = response.transactionHash,
        )
    }

    fun createDeployAccount(
        name: String? = null,
        classHash: Felt = accountContractClassHash,
        salt: Felt? = null,
        maxFee: Felt = Felt(1000000000000000),
    ): DeployAccountResult {
        val accountName = name ?: UUID.randomUUID().toString()
        val createResult = createAccount(accountName, classHash, salt)
        val details = createResult.details
        val prefundResult = prefundAccount(details.address)
        val deployResult = deployAccount(accountName, classHash, maxFee)

        return DeployAccountResult(
            details = details,
            transactionHash = deployResult.transactionHash,
        )
    }

    fun declareContract(
        contractName: String,
        maxFee: Felt = Felt(1000000000000000),
    ): DeclareContractResult {
        val params = listOf(
            "--contract-name",
            contractName,
            "--max-fee",
            maxFee.hexString(),
        )
        val response = runSnCast(
            command = "declare",
            args = params,
        ) as DeclareSnCastResponse

        return DeclareContractResult(
            classHash = response.classHash,
            transactionHash = response.transactionHash,
        )
    }

    fun deployContract(
        classHash: Felt,
        constructorCalldata: List<Felt> = emptyList(),
        salt: Felt? = null,
        unique: Boolean = false,
        maxFee: Felt = Felt(1000000000000000),
    ): DeployContractResult {
        val params = mutableListOf(
            "--class-hash",
            classHash.hexString(),
            "--max-fee",
            maxFee.hexString(),
        )
        if (constructorCalldata.isNotEmpty()) {
            params.add("--constructor-calldata")
            constructorCalldata.forEach { params.add(it.hexString()) }
        }
        if (unique) {
            params.add("--unique")
        }
        salt?.let {
            params.add("--salt")
            params.add(salt.hexString())
        }
        val response = runSnCast(
            command = "deploy",
            args = params,
        ) as DeploySnCastResponse

        return DeployContractResult(
            transactionHash = response.transactionHash,
            contractAddress = response.contractAddress,
        )
    }

    fun declareDeployContract(
        contractName: String,
        constructorCalldata: List<Felt> = emptyList(),
        salt: Felt? = null,
        unique: Boolean = false,
        maxFeeDeclare: Felt = Felt(1000000000000000),
        maxFeeDeploy: Felt = Felt(1000000000000000),
    ): DeployContractResult {
        val declareResponse = declareContract(contractName, maxFeeDeclare)
        val classHash = declareResponse.classHash
        val deployResponse = deployContract(classHash, constructorCalldata, salt, unique, maxFeeDeploy)

        return DeployContractResult(
            transactionHash = deployResponse.transactionHash,
            contractAddress = deployResponse.contractAddress,
        )
    }

    fun invokeContract(
        contractAddress: Felt,
        function: String,
        calldata: List<Felt>,
        maxFee: Felt = Felt(1000000000000000),
    ): InvokeContractResult {
        val params = mutableListOf(
            "--contract-address",
            contractAddress.hexString(),
            "--function",
            function,
            "--max-fee",
            maxFee.hexString(),
        )
        if (calldata.isNotEmpty()) {
            params.add("--calldata")
            calldata.forEach { params.add(it.hexString()) }
        }
        val response = runSnCast(
            command = "invoke",
            args = params,
        ) as InvokeSnCastResponse

        return InvokeContractResult(
            transactionHash = response.transactionHash,
        )
    }

    private fun runSnCast(command: String, args: List<String>, accountName: String = "__default__"): SnCastResponse {
        val processBuilder = ProcessBuilder(
            "sncast",
            "--json",
            "--path-to-scarb-toml",
            scarbTomlPath.absolutePathString(),
            "--accounts-file",
            accountFilePath.absolutePathString(),
            "--url",
            rpcUrl,
            "--account",
            accountName,
            command,
            *(args.toTypedArray()),
        )
        processBuilder.directory(File(contractsDirectory.absolutePathString()))

        val process = processBuilder.start()
        process.waitFor()

        val error = String(process.errorStream.readAllBytes())
        requireNoErrors(command, error)

        var result = String(process.inputStream.readAllBytes())

        // TODO: remove this - pending sncast update
        // As of sncast 0.6.0, "account create" outputs non-json data in the beggining of the response
        val index = result.indexOf('{')
        // Remove all characters before the first `{`
        result = if (index >= 0) result.substring(index) else throw IllegalArgumentException("Invalid response JSON")

        return json.decodeFromString(SnCastResponsePolymorphicSerializer, result)
    }

    private fun requireNoErrors(command: String, errorStream: String) {
        if (errorStream.isNotEmpty()) {
            throw SnCastCommandFailed(command, errorStream)
        }
    }

    private fun readAccountDetails(accountName: String): AccountDetails {
        val contents = accountFilePath.readText()
        return json.decodeFromString(AccountDetailsSerializer(accountName), contents)
    }
}
