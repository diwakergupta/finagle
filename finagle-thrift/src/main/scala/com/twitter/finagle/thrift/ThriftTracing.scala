package com.twitter.finagle.thrift

/**
 * Support for finagle tracing in thrift.
 */

import collection.JavaConversions._
import java.nio.ByteBuffer
import java.nio.charset.Charset

import org.jboss.netty.buffer.ChannelBuffers

import com.twitter.util.Time
import com.twitter.finagle.tracing.{Annotation, Span, Endpoint, Event}

private[thrift] object ThriftTracing {
  /**
   * v1: transaction id frame
   * v2: full tracing header
   * v3: b3 (big-brother-bird)
   */
  val CanTraceMethodName = "__can__finagle__trace__v3__"
}

private[thrift] object RichSpan {
  val Utf8 = Charset.forName("UTF-8")
}

private[thrift] class RichThriftSpan(self: thrift.Span) {
  import RichSpan._

  private[this] def endpointFromThrift(endpoint: thrift.Endpoint): Endpoint =
    Option(endpoint) map { e => Endpoint(e.getIpv4, e.getPort) } getOrElse Endpoint.Unknown

  /**
   * Creates a Finagle span from this thrift span.
   */
  def toFinagleSpan: Span = Span(
    _traceId    = if (self.isSetTrace_id)     Some(self.getTrace_id)     else None,
    serviceName = if (self.isSetService_name) Some(self.getService_name) else None,
    name        = if (self.isSetName)         Some(self.getName)         else None,
    id          = self.getId,
    parentId    = if (self.isSetParent_id) Some(self.getParent_id) else None,
    annotations = toAnnotations,
    children    = Seq()
  )

  /**
   * Translate this thrift-encoded span into a transcript.
   */
  def toAnnotations: Seq[Annotation] = {
    self.annotations map { annotation =>
      val value = annotation.value match {
        case thrift.Constants.CLIENT_SEND => Event.ClientSend()
        case thrift.Constants.CLIENT_RECV => Event.ClientRecv()
        case thrift.Constants.SERVER_SEND => Event.ServerSend()
        case thrift.Constants.SERVER_RECV => Event.ServerRecv()
        case value                        => Event.Message(value)
      }

      val endpoint = endpointFromThrift(annotation.host)
      Annotation(Time.fromMilliseconds(annotation.timestamp), value, endpoint)
    }
  }
}

private[thrift] class RichSpan(self: Span) {
  private[this] def endpointFromFinagle(endpoint: Endpoint): thrift.Endpoint = {
    val e = new thrift.Endpoint
    e.setIpv4(endpoint.ipv4)
    e.setPort(endpoint.port)
    e
  }

  /**
   * Translate this transcript to a set of spans. A transcript may
   * contain annotations from several spans.
   */
  def toThriftSpans: Seq[thrift.Span] = {
    val span = new thrift.Span

    span.setId(self.id)
    self.parentId foreach { span.setParent_id(_) }
    span.setTrace_id(self.traceId)

    val annotations = self.annotations map { annotation =>
      val value = annotation.event match {
        case Event.ClientSend()   => thrift.Constants.CLIENT_SEND
        case Event.ClientRecv()   => thrift.Constants.CLIENT_RECV
        case Event.ServerSend()   => thrift.Constants.SERVER_SEND
        case Event.ServerRecv()   => thrift.Constants.SERVER_RECV
        case Event.Message(value) => value
      }

      val thriftAnnotation = new thrift.Annotation
      thriftAnnotation.setTimestamp(annotation.timestamp.inMilliseconds.toLong)
      thriftAnnotation.setValue(value)
      if (annotation.endpoint != Endpoint.Unknown)
        thriftAnnotation.setHost(endpointFromFinagle(annotation.endpoint))

      thriftAnnotation
    }

    annotations foreach { span.addToAnnotations(_) }

    val childThriftSpans =
      self.children map { new RichSpan(_) } flatMap { _.toThriftSpans }
    Seq(span) ++ childThriftSpans
  }
}

private[thrift] object conversions {
  implicit def thriftSpanToRichThriftSpan(span: thrift.Span) =
    new RichThriftSpan(span)
  implicit def spanToRichSpan(span: Span) =
    new RichSpan(span)
}
