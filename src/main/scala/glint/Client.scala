package glint

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.pattern.ask
import akka.remote.RemoteScope
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import glint.exceptions.ModelCreationException
import glint.indexing.{CyclicIndexer, Indexer}
import glint.messages.master.{RegisterClient, ServerList}
import glint.models.client.async._
import glint.models.client.{BigMatrix, BigVector}
import glint.models.server._
import glint.partitioning.{Partitioner, UniformPartitioner}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.runtime.universe.{TypeTag, typeOf}

/**
  * A client interface that facilitates easy communication with the master and provides easy-to-use functions to spawn
  * large models on the parameter servers.
  *
  * A typical usage scenario (create a distributed dense array with 10000 values initialized to 0.0 and pull/push it):
  *
  * {{{
  *   val client = Client()
  *   val bigModel = client.dense[Double]("somename", 10000, 0.0)
  *
  *   bigModel.pull(Array(1, 2, 5000, 8000)).onSuccess { case values => println(values.mkString(", ")) }
  *   bigModel.push(Array(1, 2, 300, 40), Array(0.1, 0.2, 300.2, 0.001))
  * }}}
  *
  * @constructor Create a new client with optional configuration (see glint.conf for an example)
  * @param config The configuration
  * @param system The actor system
  * @param master An actor reference to the master
  */
class Client(val config: Config, val system: ActorSystem, val master: ActorRef) {

  private implicit val timeout = Timeout(config.getDuration("glint.client.default-timeout", TimeUnit.MILLISECONDS) milliseconds)
  private implicit val ec = ExecutionContext.Implicits.global

  private[glint] val actor = system.actorOf(Props[ClientActor])
  private[glint] val registration = master ? RegisterClient(actor)

  /**
    * Constructs a distributed matrix (indexed by (row: Long, col: Int)) for specified type of values
    *
    * @param rows The number of rows
    * @param cols The number of columns
    * @param modelsPerServer The number of partial models to store per parameter server (default: 1)
    * @tparam V The type of values to store
    * @return A future containing a serializable BigMatrix reference to the created models on the parameter server
    */
  def matrix[V: breeze.math.Semiring : TypeTag](rows: Long, cols: Int, modelsPerServer: Int = 1): Future[BigMatrix[V]] = {
    matrix[V](rows,
      cols,
      modelsPerServer,
      (models: Array[ActorRef]) => new CyclicIndexer(models.length, rows),
      (models: Array[ActorRef]) => new UniformPartitioner[ActorRef](models, rows))
  }

  /**
    * Constructs a distributed matrix (indexed by (row: Long, col: Int)) for specified type of values
    *
    * @param rows The number of rows
    * @param cols The number of columns
    * @param modelsPerServer The number of partial models to store per parameter server
    * @param indexer A function that creates an indexer that indexes keys into a new space
    * @param partitioner A function that creates a partitioner that partitions keys onto parameter servers
    * @tparam V The type of values to store (must be one of the following: Int, Long, Double or Float)
    * @return A future containing a serializable BigMatrix reference to the created models on the parameter server
    */
  def matrix[V: breeze.math.Semiring : TypeTag](rows: Long,
                                                cols: Int,
                                                modelsPerServer: Int,
                                                indexer: (Array[ActorRef]) => Indexer[Long],
                                                partitioner: (Array[ActorRef]) => Partitioner[ActorRef]): Future[BigMatrix[V]] = {

    // Get a list of servers
    val listOfServers = master ? new ServerList()

    // Spawn models on the servers and get a list of the models
    val listOfModels = listOfServers.mapTo[Array[ActorRef]].map { servers =>
      val nrOfServers = Math.min(rows, servers.length).toInt
      if (nrOfServers <= 0) {
        throw new ModelCreationException("Cannot create a model with 0 parameter servers")
      }
      val nrOfModels = Math.min(nrOfServers * modelsPerServer, rows).toInt
      val models = new Array[ActorRef](nrOfModels)
      models.zipWithIndex.foreach { case (_, i) => models(i) = servers(i % nrOfServers)}
      models.take(nrOfModels).zipWithIndex.map {
        case (server, index) =>
          val start = Math.ceil(index * (rows.toDouble / nrOfServers.toDouble)).toLong
          val end = Math.ceil((index + 1) * (rows.toDouble / nrOfServers.toDouble)).toLong
          val propsToDeploy = implicitly[TypeTag[V]].tpe match {
            case x if x <:< typeOf[Int] => Props(classOf[PartialMatrixInt], start, end, cols)
            case x if x <:< typeOf[Long] => Props(classOf[PartialMatrixLong], start, end, cols)
            case x if x <:< typeOf[Float] => Props(classOf[PartialMatrixFloat], start, end, cols)
            case x if x <:< typeOf[Double] => Props(classOf[PartialMatrixDouble], start, end, cols)
            case x => throw new ModelCreationException(s"Cannot create model for unsupported value type $x")
          }
          system.actorOf(propsToDeploy.withDeploy(Deploy(scope = RemoteScope(server.path.address))))
      }
    }

    // Map the list of models to a single BigModel reference
    listOfModels.map {
      case models: Array[ActorRef] =>
        implicitly[TypeTag[V]].tpe match {
          case x if x <:< typeOf[Int] => new AsyncBigMatrixInt(partitioner(models), indexer(models), config, rows, cols).asInstanceOf[BigMatrix[V]]
          case x if x <:< typeOf[Long] => new AsyncBigMatrixLong(partitioner(models), indexer(models), config, rows, cols).asInstanceOf[BigMatrix[V]]
          case x if x <:< typeOf[Float] => new AsyncBigMatrixFloat(partitioner(models), indexer(models), config, rows, cols).asInstanceOf[BigMatrix[V]]
          case x if x <:< typeOf[Double] => new AsyncBigMatrixDouble(partitioner(models), indexer(models), config, rows, cols).asInstanceOf[BigMatrix[V]]
          case x => throw new ModelCreationException(s"Cannot create model for unsupported value type $x")
        }
    }

  }

  /**
    * Constructs a distributed matrix (indexed by (row: Long, col: Int)) for specified type of values
    *
    * @param keys The number of rows
    * @param modelsPerServer The number of partial models to store per parameter server (default: 1)
    * @tparam V The type of values to store
    * @return A future containing a serializable BigMatrix reference to the created models on the parameter server
    */
  def vector[V: breeze.math.Semiring : TypeTag](keys: Long, modelsPerServer: Int = 1): Future[BigVector[V]] = {
    vector[V](keys,
      modelsPerServer,
      (models: Array[ActorRef]) => new CyclicIndexer(models.length, keys),
      (models: Array[ActorRef]) => new UniformPartitioner[ActorRef](models, keys))
  }

  /**
    * Constructs a distributed vector (indexed by key: Long) for specified type of values
    *
    * @param keys The number of keys
    * @param modelsPerServer The number of partial models to store per parameter server
    * @param indexer A function that creates an indexer that indexes keys into a new space
    * @param partitioner A function that creates a partitioner that partitions keys onto parameter servers
    * @tparam V The type of values to store (must be one of the following: Int, Long, Double or Float)
    * @return A future containing a serializable BigMatrix reference to the created models on the parameter server
    */
  def vector[V: breeze.math.Semiring : TypeTag](keys: Long,
                                                modelsPerServer: Int,
                                                indexer: (Array[ActorRef]) => Indexer[Long],
                                                partitioner: (Array[ActorRef]) => Partitioner[ActorRef]): Future[BigVector[V]] = {

    // Get a list of servers
    val listOfServers = master ? new ServerList()

    // Spawn models on the servers and get a list of the models
    val listOfModels = listOfServers.mapTo[Array[ActorRef]].map { servers =>
      val nrOfServers = Math.min(keys, servers.length).toInt
      if (nrOfServers <= 0) {
        throw new ModelCreationException("Cannot create a model with 0 parameter servers")
      }
      val nrOfModels = Math.min(nrOfServers * modelsPerServer, keys).toInt
      val models = new Array[ActorRef](nrOfModels)
      models.zipWithIndex.foreach { case (_, i) => models(i) = servers(i % nrOfServers)}
      models.take(nrOfModels).zipWithIndex.map {
        case (server, index) =>
          val start = Math.ceil(index * (keys.toDouble / nrOfServers.toDouble)).toLong
          val end = Math.ceil((index + 1) * (keys.toDouble / nrOfServers.toDouble)).toLong
          val propsToDeploy = implicitly[TypeTag[V]].tpe match {
            case x if x <:< typeOf[Int] => Props(classOf[PartialVectorInt], start, end)
            case x if x <:< typeOf[Long] => Props(classOf[PartialVectorLong], start, end)
            case x if x <:< typeOf[Float] => Props(classOf[PartialVectorFloat], start, end)
            case x if x <:< typeOf[Double] => Props(classOf[PartialVectorDouble], start, end)
            case x => throw new ModelCreationException(s"Cannot create model for unsupported value type $x")
          }
          system.actorOf(propsToDeploy.withDeploy(Deploy(scope = RemoteScope(server.path.address))))
      }
    }

    // Map the list of models to a single BigModel reference
    listOfModels.map {
      case models: Array[ActorRef] =>
        implicitly[TypeTag[V]].tpe match {
          case x if x <:< typeOf[Int] => new AsyncBigVectorInt(partitioner(models), indexer(models), config, keys).asInstanceOf[BigVector[V]]
          case x if x <:< typeOf[Long] => new AsyncBigVectorLong(partitioner(models), indexer(models), config, keys).asInstanceOf[BigVector[V]]
          case x if x <:< typeOf[Float] => new AsyncBigVectorFloat(partitioner(models), indexer(models), config, keys).asInstanceOf[BigVector[V]]
          case x if x <:< typeOf[Double] => new AsyncBigVectorDouble(partitioner(models), indexer(models), config, keys).asInstanceOf[BigVector[V]]
          case x => throw new ModelCreationException(s"Cannot create model for unsupported value type $x")
        }
    }

  }

  /**
    * Stops the glint client
    */
  def stop(): Unit = {
    system.shutdown()
  }

}

object Client {

  /**
    * Constructs a client asynchronously that succeeds once a client has registered with a master
    *
    * @param config The configuration
    * @return A future Client
    */
  def apply(config: Config): Future[Client] = {
    val default = ConfigFactory.parseResourcesAnySyntax("glint")
    val conf = config.withFallback(default).resolve()
    start(conf)
  }

  /**
    * Implementation to start a client by constructing an ActorSystem and establishing a connection to a master. It
    * creates the Client object and checks if its registration actually succeeds
    *
    * @param config The configuration
    * @return The future client
    */
  private def start(config: Config): Future[Client] = {

    // Get information from config
    val masterHost = config.getString("glint.master.host")
    val masterPort = config.getInt("glint.master.port")
    val masterName = config.getString("glint.master.name")
    val masterSystem = config.getString("glint.master.system")

    // Construct system and reference to master
    val system = ActorSystem(config.getString("glint.client.system"), config.getConfig("glint.client"))
    val master = system.actorSelection(s"akka.tcp://${masterSystem}@${masterHost}:${masterPort}/user/${masterName}")

    // Set up implicit values for concurrency
    implicit val ec = ExecutionContext.Implicits.global
    implicit val timeout = Timeout(config.getDuration("glint.client.default-timeout", TimeUnit.MILLISECONDS) milliseconds)

    // Resolve master node asynchronously
    val masterFuture = master.resolveOne()

    // Construct client based on resolved master asynchronously
    masterFuture.flatMap {
      case m =>
        val client = new Client(config, system, m)
        client.registration.map {
          case true => client
          case _ => throw new RuntimeException("Invalid client registration response from master")
        }
    }
  }
}

/**
  * The client actor class. The master keeps a death watch on this actor and knows when it is terminated. If it is
  * terminated the master can release all associated resources (e.g. BigModels on parameter servers).
  *
  * This actor either gets terminated when the system shuts down (e.g. when the Client object is destroyed) or when it
  * crashes unexpectedly.
  */
private class ClientActor extends Actor with ActorLogging {
  override def receive: Receive = {
    case x => log.info(s"Client actor received message ${x}")
  }
}
