package com.twitter.finagle.netty4.exp.pushsession

import com.twitter.finagle.{ChannelClosedException, ChannelException, Status}
import com.twitter.finagle.exp.pushsession.{PushChannelHandle, PushSession}
import com.twitter.util._
import io.netty.buffer.ByteBuf
import io.netty.channel.{
  Channel,
  ChannelHandlerContext,
  ChannelInboundHandlerAdapter,
  ChannelPipeline
}
import io.netty.handler.ssl.SslHandler
import io.netty.util
import io.netty.util.concurrent.{GenericFutureListener, Future => NettyFuture}
import java.net.SocketAddress
import java.security.cert.Certificate
import java.util.concurrent.Executor

/**
 * Netty 4 implementation of the [[PushChannelHandle]]
 *
 * It is assumed that this stage is added after any necessary layer 4 handshakes such
 * as TLS negotiation as it will set the channel auto-read to `false`. If stages before
 * it require data after this channel has been installed but before it is ready they need
 * to manage their read interests accordingly. After the [[PushSession]] is installed
 * auto-read is set to `true` and this implementation won't request or modify the read
 * interests for the remainder of its lifecycle.
 *
 * @see [[com.twitter.finagle.netty4.ssl.client.Netty4ClientSslHandler]] for the tools
 *     used to initialize TLS and delay the connect promise until negotiation is complete.
 */
private final class Netty4PushChannelHandle[In, Out] private (ch: Channel)
    extends PushChannelHandle[In, Out] {

  import Netty4PushChannelHandle._

  @volatile
  private[this] var failed: Boolean = false
  private[this] val closePromise = {
    val p = Promise[Unit]
    // Register with channel close events
    ch.closeFuture.addListener(new GenericFutureListener[NettyFuture[Void]] {
      override def operationComplete(future: NettyFuture[Void]): Unit = {
        if (future.isSuccess) p.updateIfEmpty(Return.Unit)
        else if (future.isCancelled)
          p.updateIfEmpty(Throw(new ChannelClosedException(remoteAddress)))
        else p.updateIfEmpty(Throw(ChannelException(future.cause, remoteAddress)))
      }
    })
    p
  }

  val serialExecutor: Executor = ch.eventLoop

  def onClose: Future[Unit] = closePromise

  def localAddress: SocketAddress = ch.localAddress

  def remoteAddress: SocketAddress = ch.remoteAddress

  // lazy because it's not all that common for it to be called
  lazy val peerCertificate: Option[Certificate] = for {
    handler <- Option(ch.pipeline.get(classOf[SslHandler]))
    certs <- Try(handler.engine.getSession.getPeerCertificates).toOption
    head <- certs.headOption
  } yield head

  def status: Status =
    if (failed || !ch.isOpen) Status.Closed
    else Status.Open

  // All send methods need to schedule their writes in the executor to guarantee
  // that they are ordered even if the call to send doesn't originate from the
  // serial executor itself. This is because Netty will fast track writes that
  // happen to be on the right thread and defer others by scheduling them. The
  // only way to make sure it's totally fair is to push everyone through the
  // Executor regardless of the thread calling send.
  def send(messages: Iterable[Out])(continuation: (Try[Unit]) => Unit): Unit = {
    if (messages.isEmpty) {
      // We schedule it with the executor so as to satisfy the guarantee that the
      // continuation will be run later.
      serialExecutor.execute(new Runnable {
        def run(): Unit = continuation(Return.Unit)
      })
    } else
      serialExecutor.execute(new Runnable {
        def run(): Unit = {
          // When doing a batch write we only have a single handle to all writes, so if
          // one fails there is no way to say which one and there is no reason to continue.
          // Therefore, we let all but the last use the voidPromise which will fail the
          // whole channel if they fail and send the last one with a normal promise.
          val it = messages.iterator

          // We cache one message so that we can flush the last element
          var next = it.next()
          while (it.hasNext) {
            ch.write(next, ch.voidPromise())
            next = it.next()
          }

          // The standard flush behavior will suffice for the last message
          handleWriteAndFlush(next, continuation)
        }
      })
  }

  // See note above about the scheduling of send messages
  def send(message: Out)(continuation: (Try[Unit]) => Unit): Unit = {
    serialExecutor.execute(new Runnable {
      def run(): Unit = {
        handleWriteAndFlush(message, continuation)
      }
    })
  }

  // See note above about the scheduling of send messages
  def sendAndForget(message: Out): Unit =
    serialExecutor.execute(new Runnable {
      def run(): Unit = ch.writeAndFlush(message, ch.voidPromise())
    })

  // See note above about the scheduling of send messages
  def sendAndForget(messages: Iterable[Out]): Unit =
    if (messages.nonEmpty) serialExecutor.execute(new Runnable {
      def run(): Unit = {
        val it = messages.iterator
        // Cache one element in `next` so we can flush the last one
        var next = it.next()
        while (it.hasNext) {
          ch.write(next, ch.voidPromise())
          next = it.next()
        }

        ch.writeAndFlush(next, ch.voidPromise())
      }
    })

  // We don't have any scarce resources that need some deadline to cleanup
  // so we just clean everything up now.
  def close(deadline: Time): Future[Unit] = {
    if (ch.isOpen) ch.close()
    onClose.unit
  }

  def registerSession(newSession: PushSession[In, Out]): Unit = {
    ch.pipeline.get(classOf[SessionDriver]) match {
      case null =>
        throw new IllegalStateException(
          "Tried to replace the PushSession when the previous session hasn't been installed"
        )
      case driver => driver.registerSession(newSession)
    }
  }

  // It is expected that this will be executed from a task explicitly scheduled in
  // the serial executor which means that it was run 'later' from whatever action on
  // the Netty4PushTransport scheduled it since scheduling shouldn't result in the
  // task being run before the `.execute` method returns.
  private[this] def handleWriteAndFlush(
    message: Out,
    continuation: Try[Unit] => Unit
  ): Unit = {
    val op = ch.writeAndFlush(message)
    if (op.isSuccess) continuation(Return.Unit)
    else
      op.addListener(new GenericFutureListener[util.concurrent.Future[Void]] {
        def operationComplete(future: util.concurrent.Future[Void]): Unit = {
          val result =
            if (future.isSuccess) Return.Unit
            else {
              val exc = ChannelException(future.cause, remoteAddress)
              handleFail(Some(exc))
              Throw(exc)
            }

          continuation(result)
        }
      })
  }

  // Must be called from within the serialExecutor
  private def handleFail(exc: Option[Throwable]): Unit =
    if (!failed) {
      failed = true
      // We trampoline the satisfaction of the close promise to make sure
      // users don't get inadvertant re-entrance due to the continuations
      // attached to the promise potentially being run right away.
      serialExecutor.execute(new Runnable {
        def run(): Unit = {
          closePromise.updateIfEmpty(exc match {
            case Some(t) => Throw(t)
            case None => Return.Unit
          })
        }
      })

      close()
    }

  private[this] def handleChannelExceptionCaught(exc: Throwable): Unit = {
    // We make sure these events are trampolined through the serial executor
    // to guard against re-entrance.
    serialExecutor.execute(new Runnable {
      def run(): Unit = handleFail(Some(ChannelException(exc, remoteAddress)))
    })
  }

  private[this] def handleChannelInactive(): Unit = {
    // We make sure these events are trampolined through the serial executor
    // to guard against re-entrance.
    serialExecutor.execute(new Runnable {
      def run(): Unit = handleFail(None)
    })
  }

  private[this] final class SessionDriver(@volatile private var session: PushSession[In, Out])
      extends ChannelInboundHandlerAdapter {

    // in service of the `PushChannelHandle.registerSession` method
    def registerSession(newSession: PushSession[In, Out]): Unit = {
      session = newSession
    }

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val m = msg.asInstanceOf[In]
      serialExecutor.execute(new Runnable {
        def run(): Unit = session.receive(m)
      })
    }

    override def channelInactive(ctx: ChannelHandlerContext): Unit =
      handleChannelInactive()

    override def exceptionCaught(ctx: ChannelHandlerContext, e: Throwable): Unit =
      handleChannelExceptionCaught(e)
  }

  // This is a helper ChannelHandler to guard against the socket sending messages to the
  // session before its ready. In the common case this likely doesn't actually catch any
  // messages but is here for corner cases such as a chunk of data following right after
  // the TLS handshake and before we've had a chance to turn auto-read off.
  private final class DelayedByteBufHandler extends ChannelInboundHandlerAdapter {
    // 8 is the minimum initial size allowed by the ArrayDeque implementation
    private[this] val pendingMessages = new java.util.ArrayDeque[ByteBuf](8)
    private[this] var ctx: ChannelHandlerContext = null

    /** Removes itself from the pipeline and sends its messages */
    def installSessionDriver(session: PushSession[In, Out]): Unit = {
      assert(ctx != null)
      ch.pipeline.addLast(SessionDriver, new SessionDriver(session))
      ch.pipeline.remove(this)
      ch.config.setAutoRead(true)

      // Empty our queue
      while (!pendingMessages.isEmpty) {
        ctx.fireChannelRead(pendingMessages.poll())
      }
    }

    override def handlerAdded(ctx: ChannelHandlerContext): Unit = {
      this.ctx = ctx
      ch.config.setAutoRead(false)
    }

    // Need to make sure to release anything that is still laying around
    override def channelInactive(ctx: ChannelHandlerContext): Unit = {
      while (!pendingMessages.isEmpty) {
        pendingMessages.poll().release()
      }

      handleChannelInactive()
    }

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = msg match {
      case bb: ByteBuf =>
        pendingMessages.add(bb)
      case other =>
        val ex = new IllegalStateException(s"Received unexpected message: $other")
        ctx.fireExceptionCaught(ex)
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: Throwable): Unit =
      handleChannelExceptionCaught(e)
  }
}

private object Netty4PushChannelHandle {
  val SessionDriver: String = "pushSessionDriver"
  val DelayedByteBufHandler: String = "delayedByteBufHandler"

  /**
   * Install a [[Netty4PushChannelHandle]] in the pipeline and wire up lifetime events
   *
   * Protocol related netty pipeline initialization is deferred until the session has
   * resolved. The resultant `Future[T]` will resolve once the session has been installed
   * and the pipeline is receiving events from the socket.
   */
  def install[In, Out, T <: PushSession[In, Out]](
    ch: Channel,
    protocolInit: ChannelPipeline => Unit,
    sessionFactory: PushChannelHandle[In, Out] => Future[T]
  ): (Netty4PushChannelHandle[In, Out], Future[T]) = {

    val p = Promise[T]
    p.setInterruptHandler { case _ => ch.close() }

    val channelHandle = new Netty4PushChannelHandle[In, Out](ch)
    val delayStage = new channelHandle.DelayedByteBufHandler
    ch.pipeline.addLast(DelayedByteBufHandler, delayStage)

    // Link resolution of the PushSession to installing the rest of the pipeline
    sessionFactory(channelHandle).respond { result =>
      ch.eventLoop.execute(new Runnable {
        def run(): Unit = {
          result match {
            case Return(session) =>
              protocolInit(ch.pipeline)
              delayStage.installSessionDriver(session)

            case Throw(exc) =>
              channelHandle.handleFail(Some(exc))

          }
          p.updateIfEmpty(result)
        }
      })
    }

    channelHandle -> p
  }
}