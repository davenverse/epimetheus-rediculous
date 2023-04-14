package io.chrisdavenport.epimetheus.rediculous

import cats.effect.implicits._
import cats.syntax.all._
import cats.effect._
import io.chrisdavenport.epimetheus._
import io.chrisdavenport.rediculous._
import cats.effect.kernel.Outcome.Errored
import cats.effect.kernel.Outcome.Succeeded
import cats.effect.kernel.Outcome.Canceled

object RediculousMetrics {
  /**
    * [[measuredByName]] except automatically applies the name "default".
    * As metrics can only be registered once, these methods should be used as either or.
    *
    * @param collector The CollectorRegistry
    * @return A function which turn a redisConnection into one that measures
    */
  def measured[F[_]: Async](
    collector: CollectorRegistry[F]
  ): F[RedisConnection[F] => RedisConnection[F]] = {
    measuredByName(collector).map(_("default"))
  }

  /**
    * This builds 3 independent metrics.
    * First, a Histogram which looks at overall time of the operations.
    * Secondly, a count of the total amount of time by operation
    * Lastly, a count of the total amount of operations
    * 
    * The first is a generally useful tool without exploding your
    * arity. The second two build enough context to get an average
    * of each operations total time which may tell you useful information
    * about the operation of how your service is interacting with redis.
    * 
    * As metrics can only be registered once, these methods should be used as either or.
    *
    * @param collector The CollectorRegistry
    * @return A function which turn a redisConnection into one that measures
    */
  def measuredByName[F[_]: Async](
    collector: CollectorRegistry[F]
  ): F[String => RedisConnection[F] => RedisConnection[F]] = {
    for {
      durationHistogram <- Histogram.labelled(
        collector,
        Name("rediculous_duration_seconds"),
        "Rediculous Seconds Spent Per Operation",
        Sized(Label("name")),
        {(name: String) => Sized(name)},
      )
      operationTime <- Counter.labelled(
        collector, 
        Name("rediculous_operation_seconds_total"),
        "Rediculous Seconds Spent During Each Operation Total",
        Sized(Label("name"), Label("operation"), Label("outcome")),
        { (t: (String, String, String)) => t match {
          case (name, operation, outcome) => Sized(name, operation, outcome)}
        }
      )
      operationCount <- Counter.labelled(
        collector, 
        Name("rediculous_operation_count_total"),
        "Rediculous Count Of Each Operation",
        Sized(Label("name"), Label("operation"), Label("outcome")),
        { (t: (String, String, String)) => t match {
          case (name, operation, outcome) => Sized(name, operation, outcome)}
        }
      )
    } yield {(name: String) => (redisConnection: RedisConnection[F]) => 

      new RedisConnection[F] {
        def runRequest(
          inputs: fs2.Chunk[cats.data.NonEmptyList[scodec.bits.ByteVector]],
          key: Option[scodec.bits.ByteVector]
        ): F[fs2.Chunk[Resp]] = {
          val operations = inputs.map(_.head.decodeUtf8.getOrElse("unknown"))
          Clock[F].monotonic.flatMap{ start => 
            redisConnection.runRequest(inputs, key).guaranteeCase{
              outcome => 
                Clock[F].monotonic.flatMap{ end => 
                  val elapsed = (end - start).toNanos.toDouble
                  val elapsedInSeconds = elapsed / 1000000000
                  val outcomeString = outcomeToString(outcome)
                  operations.traverse_( operation => 
                    durationHistogram.label(name).observe(elapsedInSeconds) >>
                    operationCount.label((name, operation, outcomeString)).inc >>
                    operationTime.label((name, operation, outcomeString)).incBy(elapsedInSeconds)
                  )
                }
            }
          }
        }
      }
    }
  }

  private def outcomeToString[F[_], E, A](outcome: Outcome[F, E, A]): String = outcome match {
    case Succeeded(_) => "succeeded"
    case Errored(_) => "errored"
    case Canceled() => "canceled"
  }
}