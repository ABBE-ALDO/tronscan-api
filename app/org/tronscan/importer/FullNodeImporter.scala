package org
package tronscan.importer

import akka.actor.Actor
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, Merge, Sink, Source}
import akka.{Done, NotUsed}
import javax.inject.Inject
import monix.execution.Scheduler.Implicits.global
import org.tron.protos.Tron.Transaction.Contract.ContractType.{TransferAssetContract, TransferContract, WitnessCreateContract}
import org.tron.protos.Tron.{Block, Transaction}
import org.tronscan.Extensions._
import org.tronscan.domain.Types.Address
import org.tronscan.grpc.{FullNodeBlockChain, WalletClient}
import org.tronscan.importer.ImportManager.Sync
import org.tronscan.models._
import org.tronscan.service.SynchronisationService
import org.tronscan.utils.ModelUtils
import play.api.Logger
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi
import play.api.inject.ConfigurationProvider
import slick.dbio.{Effect, NoStream}
import slick.sql.FixedSqlAction

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class FullNodeImporter @Inject()(
  blockModelRepository: BlockModelRepository,
  transactionModelRepository: TransactionModelRepository,
  transferRepository: TransferModelRepository,
  witnessModelRepository: WitnessModelRepository,
  walletClient: WalletClient,
  syncService: SynchronisationService,
  databaseImporter: DatabaseImporter,
  blockChainBuilder: BlockChainStreamBuilder,
  @NamedCache("redis") redisCache: CacheAsyncApi,
  configurationProvider: ConfigurationProvider) extends Actor {

  val config = configurationProvider.get
  val syncFull = configurationProvider.get.get[Boolean]("sync.full")
  val syncSolidity = configurationProvider.get.get[Boolean]("sync.solidity")

  val decider: Supervision.Decider = { exc =>
    Logger.error("FULL NODE ERROR", exc)
    Supervision.Resume
  }

  implicit val materializer = ActorMaterializer(
    ActorMaterializerSettings(context.system)
      .withSupervisionStrategy(decider))(context)

  /**
    * Build import action from import status
    */
  def buildImportActionFromImportStatus(importStatus: ImportStatus) = {
    var autoConfirmBlocks = false
    var updateAccounts = false
    var redisCleaner = true
    var asyncAddressImport = false
    var publishEvents = true

    val fullNodeBlockHash = awaitSync(syncService.getFullNodeHashByNum(importStatus.solidityBlock))
    val resetDB = !awaitSync(syncService.isSameChain())

    // If solidity isn't being synced then take over Solidity node tasks
    if (!importStatus.solidityEnabled) {
      autoConfirmBlocks = true
      updateAccounts = true
    }

    // If the solidity and full node hash are the same then confirm everything
    if (fullNodeBlockHash == importStatus.solidityBlockHash) {
      autoConfirmBlocks = true
      updateAccounts = true
    }

    // Don't publish events when there is lots to sync
    if (importStatus.dbLatestBlock < (importStatus.fullNodeBlock - 1000)) {
      publishEvents = false
    }

    // No need to clean cache when starting a clean sync
    if (importStatus.dbLatestBlock == 0) {
      redisCleaner = false
    }

    ImportAction(
      confirmBlocks       = autoConfirmBlocks,
      updateAccounts      = updateAccounts,
      cleanRedisCache     = redisCleaner,
      asyncAddressImport  = asyncAddressImport,
      publishEvents       = publishEvents,
      resetDB             = resetDB
    )
  }

  def buildSource(importState: ImportStatus) = {

    Logger.info("buildSource: " + importState.toString)

    val importAction = buildImportActionFromImportStatus(importState)

    if (importAction.resetDB) {
      awaitSync(syncService.resetDatabase())
    }

    def redisCleaner = if (importAction.cleanRedisCache) Flow[Address].alsoTo(redisCacheCleaner) else Flow[Address]

    def accountUpdaterFlow: Flow[Address, Any, NotUsed] = {
      if (importAction.updateAccounts) {
        if (importAction.asyncAddressImport) {
          syncService.buildAddressSynchronizer()
        } else {
          syncService.buildAddressSynchronizer()
        }
      } else {
        Flow[Address]
      }
    }

    def eventsPublisher = {
      if (importAction.publishEvents) {
        blockChainBuilder.publishContractEvents(List(
          TransferContract,
          TransferAssetContract,
          WitnessCreateContract
        ))
      } else {
        Flow[Transaction.Contract]
      }
    }


    val blockSink = Sink.fromGraph(GraphDSL.create(Sink.ignore) { implicit b =>sink =>
        import GraphDSL.Implicits._
        val blocks = b.add(Broadcast[Block](3))
        val transactions = b.add(Broadcast[(Block, Transaction)](1))
        val contracts = b.add(Broadcast[(Block, Transaction, Transaction.Contract)](2))
        val addresses = b.add(Merge[Address](2))
        val out = b.add(Merge[Any](3))

        /***** Channels *****/

        // Pass block witness addresses to address stream
        blocks.map(_.witness) ~> addresses

        // Transactions
        blocks.mapConcat(b => b.transactions.map(t => (b, t)).toList) ~> transactions.in

        // Contracts
        transactions.mapConcat { case (block, t) => t.getRawData.contract.map(c => (block, t, c)).toList } ~> contracts.in

        // Read addresses from contracts
        contracts.mapConcat(_._3.addresses) ~> addresses

        /** Importers **/

        // Synchronize Blocks
        blocks ~> fullNodeBlockImporter(importAction.confirmBlocks) ~> out

        // Sync addresses
        addresses ~> redisCleaner ~> accountUpdaterFlow ~> out

        // Broadcast contract events
        contracts.map(_._3) ~> eventsPublisher ~> out

        /** Close Stream **/

        // Route everything to sink
        out ~> sink.in

        SinkShape(blocks.in)
    })


    Source
      .single(importState)
      .via(syncStarter)
      .via(readBlocksFromStatus)
      .toMat(blockSink)(Keep.right)
  }

  /**
    * Invalidate addresses in the redis cache
    */
  def redisCacheCleaner: Sink[Any, Future[Done]] = Sink
    .foreach { address =>
      redisCache.removeMatching(s"address/$address/*")
    }

  /**
    * Build a stream of blocks from the given import status
    */
  def readBlocksFromStatus = Flow[ImportStatus]
    .mapAsync(1) { status =>
      walletClient.full.map { walletFull =>
        val fullNodeBlockChain = new FullNodeBlockChain(walletFull)

        // Switch between batch or single depending how far the sync is behind
        if (status.fullNodeBlocksToSync < 100)  blockChainBuilder.readFullNodeBlocks(status.dbLatestBlock + 1, status.fullNodeBlock)(fullNodeBlockChain.client)
        else                                    blockChainBuilder.readFullNodeBlocksBatched(status.dbLatestBlock + 1, status.fullNodeBlock, 100)(fullNodeBlockChain.client)
      }
    }
    .flatMapConcat { blockStream => blockStream }

  /**
    * Retrieves the latest synchronisation status and checks if the sync should proceed
    */
  def syncStarter = Flow[ImportStatus]
    .filter {
      // Stop if there are more then 100 blocks to sync for full node
      case status if status.fullNodeBlocksToSync > 0 =>
        Logger.info(s"START SYNC FROM ${status.dbLatestBlock} TO ${status.fullNodeBlock}. " + status.toString)
        true
      case status =>
        Logger.info("IGNORE FULL NODE SYNC: " + status.toString)
        false
    }


  def buildContractSqlBuilder = {
    import databaseImporter._
    importWitnessCreate orElse importTransfers orElse buildConfirmedEvents orElse elseEmpty
  }

  /**
    * Build block importer
    *
    * @param confirmBlocks if all blocks that are being imported should be automatically confirmed
    */
  def fullNodeBlockImporter(confirmBlocks: Boolean = false) = {
    val importer = buildContractSqlBuilder

    Flow[Block]
      .map { block =>

        val header = block.getBlockHeader.getRawData
        val queries: ListBuffer[FixedSqlAction[_, NoStream, Effect.Write]] = ListBuffer()

        Logger.info(s"FULL NODE BLOCK: ${header.number}, TX: ${block.transactions.size}, CONFIRM: $confirmBlocks")

        // Import Block
        queries.append(blockModelRepository.buildInsert(BlockModel.fromProto(block).copy(confirmed = confirmBlocks)))

        // Import Transactions
        queries.appendAll(block.transactions.map { trx =>
          transactionModelRepository.buildInsertOrUpdate(ModelUtils.transactionToModel(trx, block).copy(confirmed = confirmBlocks))
        })

        // Import Contracts
        queries.appendAll(block.transactionContracts.flatMap {
          case (trx, contract) =>
            ModelUtils.contractToModel(contract, trx, block).map {
              case transfer: TransferModel =>
                importer((contract.`type`, contract, transfer.copy(confirmed = confirmBlocks || block.getBlockHeader.getRawData.number == 0)))
              case x =>
                importer((contract.`type`, contract, x))
            }.getOrElse(Seq.empty)
        })

        queries.toList
      }
      // Flatmap the queries
      .flatMapConcat(q => Source(q))
      // Batch queries together
      .groupedWithin(1000, 2.seconds)
      // Insert batched queries in database
      .mapAsync(1)(blockModelRepository.executeQueries)
  }

  def startSync() = {

    Logger.info("START FULL NODE SYNC")

    Source.tick(0.seconds, 2.seconds, "")
      .mapAsync(1)(_ => syncService.importStatus.flatMap(buildSource(_).run()))
      .runWith(Sink.ignore)
      .andThen {
        case Success(_) =>
          Logger.info("BLOCKCHAIN SYNC SUCCESS")
        case Failure(exc) =>
          Logger.error("BLOCKCHAIN SYNC FAILURE", exc)
      }
  }

  def receive = {
    case Sync() =>
      startSync()
  }
}
