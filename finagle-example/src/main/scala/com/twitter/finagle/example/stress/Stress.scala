package com.twitter.finagle.example.stress

import com.twitter.concurrent.AsyncSemaphore
import com.twitter.finagle.builder.{Http, ClientBuilder}
import com.twitter.finagle.Service
import com.twitter.util.{Promise, Time, Future, MapMaker}
import java.net.{InetSocketAddress, URI}
import java.util.concurrent.atomic.AtomicInteger
import org.jboss.netty.handler.codec.http._

/**
 * A program to stress an HTTP server. The code below throttles request using an
 * asynchronous semaphore. Specify the uri, concurrency level,  and the total number
 * of requests at the command line.
 */
object Stress {
  def main(args: Array[String]) {
    val uri           = new URI(args(0))
    val concurrency   = args(1).toInt
    val totalRequests = args(2).toInt

    val errors    = new AtomicInteger(0)
    val responses = MapMaker[HttpResponseStatus, AtomicInteger] { config =>
      config.compute { k =>
        new AtomicInteger(0)
      }
    }

    val request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri.getPath)
    HttpHeaders.setHost(request, uri.getHost)

    val client: Service[HttpRequest, HttpResponse] = ClientBuilder()
      .codec(Http)
      .hosts(new InetSocketAddress(uri.getHost, uri.getPort))
      .hostConnectionCoresize(concurrency)
      .hostConnectionLimit(concurrency)
      .build()


    val completedRequests = new AtomicInteger(0)

    val requests = Future.parallel(concurrency) {
      Future.times(totalRequests / concurrency) {
        client(request) onSuccess { response =>
          responses(response.getStatus).incrementAndGet()
        } handle { case e =>
          errors.incrementAndGet()
        } ensure {
          completedRequests.incrementAndGet()
        }
      }
    }

    val start = Time.now

    Future.join(requests) ensure {
      client.release()

      val duration = start.untilNow
      println("%20s\t%s".format("Status", "Count"))
      for ((status, stat) <- responses) {
        println("%20s\t%d".format(status, stat.get))
      }
      println("================")
      println("%d requests completed in %dms (%f requests per second)".format(
        completedRequests.get, duration.inMilliseconds, totalRequests.toFloat / duration.inMillis.toFloat * 1000))
      println("%d errors".format(errors.get))
    }
  }

}
