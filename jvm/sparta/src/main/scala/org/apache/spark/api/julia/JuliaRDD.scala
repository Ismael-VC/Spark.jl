package org.apache.spark.api.julia

import java.io._
import java.net._
import java.util
import java.util.Collections
import java.util.{ArrayList => JArrayList}
import java.util.{List => JList}
import java.util.{Map => JMap}

import com.google.common.base.Charsets.UTF_8
import org.apache.hadoop.mapreduce.{InputFormat => NewInputFormat}
import org.apache.hadoop.mapreduce.{OutputFormat => NewOutputFormat}
import org.apache.spark._
import org.apache.spark.api.java.JavaRDD
import org.apache.spark.api.java.JavaSparkContext
import org.apache.spark.input.PortableDataStream
import org.apache.spark.rdd.RDD
import org.apache.spark.util.{RedirectThread, Utils}

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.language.existentials
import scala.util.control.NonFatal

class JuliaRDD(
    @transient parent: RDD[_],
    command: Array[Byte]
) extends RDD[Array[Byte]](parent) {

  val preservePartitioning = true

  val bufferSize = 65536
  val reuseWorker = true

  override def getPartitions: Array[Partition] = firstParent.partitions

  override val partitioner: Option[Partitioner] = {
    if (preservePartitioning) firstParent.partitioner else None
  }

  override def compute(split: Partition, context: TaskContext): Iterator[Array[Byte]] = {
    val env = SparkEnv.get
    val worker: Socket = JuliaRDD.createWorker()
    // Start a thread to feed the process input from our parent's iterator
    val writerThread = new WriterThread(env, worker, split, context)
    writerThread.start()
    // Return an iterator that read lines from the process's stdout
    val stream = new DataInputStream(new BufferedInputStream(worker.getInputStream, bufferSize))
    val stdoutIterator = new Iterator[Array[Byte]] {
      override def next(): Array[Byte] = {
        val obj = _nextObj
        if (hasNext) {
          _nextObj = read()
        }
        obj
      }

      private def read(): Array[Byte] = {
        if (writerThread.exception.isDefined) {
          throw writerThread.exception.get
        }
        try {
          stream.readInt() match {
            case length if length > 0 =>
              val obj = new Array[Byte](length)
              stream.readFully(obj)
              println("OBJECT: " + new String(obj, "UTF-8"))
              obj
            case 0 => Array.empty[Byte]
            case SpecialLengths.JULIA_EXCEPTION_THROWN =>
              // Signals that an exception has been thrown in julia
              val exLength = stream.readInt()
              val obj = new Array[Byte](exLength)
              stream.readFully(obj)
              throw new Exception(new String(obj, UTF_8),
                writerThread.exception.getOrElse(null))
            case SpecialLengths.END_OF_DATA_SECTION =>
              if (stream.readInt() == SpecialLengths.END_OF_STREAM) {
                null
              } else {
                throw new RuntimeException("Protocol error")
              }

          }
        } catch {

          case e: Exception if context.isInterrupted =>
            logDebug("Exception thrown after task interruption", e)
            throw new TaskKilledException

          case e: Exception if env.isStopped =>
            logDebug("Exception thrown after context is stopped", e)
            null  // exit silently

          case e: Exception if writerThread.exception.isDefined =>
            logError("Julia worker exited unexpectedly (crashed)", e)
            logError("This may have been caused by a prior exception:", writerThread.exception.get)
            throw writerThread.exception.get

          case eof: EOFException =>
            throw new SparkException("Julia worker exited unexpectedly (crashed)", eof)
        }
      }

      var _nextObj = read()

      override def hasNext: Boolean = _nextObj != null
    }
    new InterruptibleIterator(context, stdoutIterator)
  }

  val asJavaRDD : JavaRDD[Array[Byte]] = JavaRDD.fromRDD(this)

  /**
   * The thread responsible for writing the data from the JuliaRDD's parent iterator to the
   * Julia process.
   */
  class WriterThread(env: SparkEnv, worker: Socket, split: Partition, context: TaskContext)
    extends Thread(s"stdout writer for julia") {

    @volatile private var _exception: Exception = null

    /** Contains the exception thrown while writing the parent iterator to the Julia process. */
    def exception: Option[Exception] = Option(_exception)

    /** Terminates the writer thread, ignoring any exceptions that may occur due to cleanup. */
    def shutdownOnTaskCompletion() {
      assert(context.isCompleted)
      this.interrupt()
    }

    override def run(): Unit = Utils.logUncaughtExceptions {
      try {
        val stream = new BufferedOutputStream(worker.getOutputStream, bufferSize)
        val dataOut = new DataOutputStream(stream)
        // partition index
        dataOut.writeInt(split.index)
        dataOut.flush()
        // serialized command:
        dataOut.writeInt(command.length)
        dataOut.write(command)
        dataOut.flush()
        // data values
        JuliaRDD.writeIteratorToStream(firstParent.iterator(split, context), dataOut)
        dataOut.writeInt(SpecialLengths.END_OF_DATA_SECTION)
        dataOut.writeInt(SpecialLengths.END_OF_STREAM)
        dataOut.flush()
      } catch {
        case e: Exception if context.isCompleted || context.isInterrupted =>
          logDebug("Exception thrown after task completion (likely due to cleanup)", e)
          if (!worker.isClosed) {
            Utils.tryLog(worker.shutdownOutput())
          }

        case e: Exception =>
          // We must avoid throwing exceptions here, because the thread uncaught exception handler
          // will kill the whole executor (see org.apache.spark.executor.Executor).
          _exception = e
          if (!worker.isClosed) {
            Utils.tryLog(worker.shutdownOutput())
          }
      } finally {
        // Release memory used by this thread for shuffles
        env.shuffleMemoryManager.releaseMemoryForThisThread()
        // Release memory used by this thread for unrolling blocks
        env.blockManager.memoryStore.releaseUnrollMemoryForThisThread()
      }
    }
  }

}

private object SpecialLengths {
  val END_OF_DATA_SECTION = -1
  val JULIA_EXCEPTION_THROWN = -2
  val TIMING_DATA = -3
  val END_OF_STREAM = -4
  val NULL = -5
}

object JuliaRDD extends Logging {

  def fromJavaRDD[T](javaRdd: JavaRDD[T], command: Array[Byte]): JuliaRDD = new JuliaRDD(JavaRDD.toRDD(javaRdd), command)

  def createWorker(): Socket = {
    var serverSocket: ServerSocket = null
    try {
      serverSocket = new ServerSocket(0, 1, InetAddress.getByAddress(Array(127, 0, 0, 1).map(_.toByte)))

      // Create and start the worker
      val pb = new ProcessBuilder(Seq("julia", "-e", "using Sparta; Sparta.launch_worker()"))
      // val workerEnv = pb.environment()
      // workerEnv.putAll(envVars)
      val worker = pb.start()

      // Redirect worker stdout and stderr
      redirectStreamsToStderr(worker.getInputStream, worker.getErrorStream)

      // Tell the worker our port
      val out = new OutputStreamWriter(worker.getOutputStream)
      out.write(serverSocket.getLocalPort + "\n")
      out.flush()

      // Wait for it to connect to our socket
      serverSocket.setSoTimeout(10000)
      try {
        val socket = serverSocket.accept()
        // workers.put(socket, worker)
        return socket
      } catch {
        case e: Exception =>
          throw new SparkException("Julia worker did not connect back in time", e)
      }
    } finally {
      if (serverSocket != null) {
        serverSocket.close()
      }
    }
    null
  }

  /**
   * Redirect the given streams to our stderr in separate threads.
   */
  private def redirectStreamsToStderr(stdout: InputStream, stderr: InputStream) {
    try {
      new RedirectThread(stdout, System.err, "stdout reader for julia").start()
      new RedirectThread(stderr, System.err, "stderr reader for julia").start()
    } catch {
      case e: Exception =>
        logError("Exception in redirecting streams", e)
    }
  }


  def writeIteratorToStream[T](iter: Iterator[T], dataOut: DataOutputStream) {

    def write(obj: Any): Unit = obj match {
      case arr: Array[Byte] =>
      case other =>
        throw new SparkException("Unexpected element type " + other.getClass)
    }

    iter.foreach(write)
  }

}

