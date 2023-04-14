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
  def measured[F[_]: Async](
    collector: CollectorRegistry[F]
  ): F[RedisConnection[F] => RedisConnection[F]] = {
    measuredByName(collector).map(_("default"))
  }

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
        { case (name: String, operation: String, outcome: String) => Sized(name, operation, outcome)}
      )
      operationCount <- Counter.labelled(
        collector, 
        Name("rediculous_operation_count_total"),
        "Rediculous Count Of Each Operation",
        Sized(Label("name"), Label("operation"), Label("outcome")),
        {case (name: String, operation: String, outcome: String) => Sized(name, operation, outcome)}
      )
    } yield {(name: String) => (redisConnection: RedisConnection[F]) => 

      new RedisConnection[F] {
        def runRequest(
          inputs: fs2.Chunk[cats.data.NonEmptyList[scodec.bits.ByteVector]],
          key: Option[scodec.bits.ByteVector]
        ): F[fs2.Chunk[Resp]] = {
          val operations = inputs.map(_.head.decodeUtf8.getOrElse("unknown"))
          Sync[F].delay(System.nanoTime()).flatMap{ start => 
            redisConnection.runRequest(inputs, key).guaranteeCase{
              outcome => 
                Sync[F].delay(System.nanoTime()).flatMap{ end => 
                  val elapsed = (end - start).toDouble
                  val elapsedInSeconds = elapsed / 1000000000
                  operations.traverse_( operation => 
                    durationHistogram.label(name).observe(elapsedInSeconds) >>
                    operationCount.label((name, operation, outcome)).inc >>
                    operationTime.label((name, operation, outcome)).incBy(elapsedInSeconds)
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