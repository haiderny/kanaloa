package kanaloa.reactive.dispatcher

import akka.actor._
import kanaloa.reactive.dispatcher.PerformanceSampler.Sample
import java.time.{LocalDateTime ⇒ Time}
import kanaloa.reactive.dispatcher.Regulator.Status
import kanaloa.reactive.dispatcher.metrics.Metric

import scala.concurrent.duration._
import Regulator._
import Types._
import kanaloa.util.Java8TimeExtensions._
/**
 * A traffic regulator based on the PIE algo (Proportional Integral controller Enhanced)
 * suggested in this paper https://www.ietf.org/mail-archive/web/iccrg/current/pdfB57AZSheOH.pdf by Rong Pan and his collaborators.
 * The algo drop request with a probability, here is the pseudocode
 * Every update interval Tupdate
 *   1. Estimation current queueing delay
 *      currentDelay = queueLength / averageDequeueRate
 *   2. Based on current drop probability, p, determine suitable step scales:
 *      if p < 1%       :  α = α΄ / 8, β = β΄ / 8
 *      else if p < 10% :  α = α΄ / 2, β = β΄  2
 *      else            :  α = α΄,  β = β΄
 *   3, Calculate drop probability as:
 *      p = p
 *         + α * (currentDelay - referenceDelay) / referenceDelay
 *         + β * (currentDelay - oldDelay) / referenceDelay
 *   4, Update previous delay sample rate as
 *      OldDelay - currentDelay
 * The regulator allows for a burst, here is the calculation
 *   1. if burstAllowed > 0
 *        enqueue request bypassing random drop
 *   2. upon Tupdate
 *      if p == 0 and currentDelay < referenceDelay / 2 and oldDelay < referenceDelay / 2
 *         burstAllowed = maxBurst
 *      else
 *         burstAllowed = burstAllowed - timePassed (roughly Tupdate)
 *
 * @param metricsCollector [[PerformanceSampler]] actor that provides Performance samples,
 *               this also controls the TupdateRate with frequency of samples
 * @param regulatee [[PushingDispatcher]] actor that receive the dropping probability update
 */
class Regulator(settings: Settings, metricsCollector: ActorRef, regulatee: ActorRef) extends Actor with ActorLogging {

  override def preStart(): Unit = {
    super.preStart()
    metricsCollector ! PerformanceSampler.Subscribe(self)
  }

  def receive: Receive = {
    case s: Sample ⇒
      context become regulating(Status(
        delay = estimateDelay(s.queueLength, s.speed),
        droppingRate = DroppingRate(0),
        burstDurationLeft = settings.durationOfBurstAllowed,
        averageSpeed = s.speed
      ))
  }

  def regulating(status: Status): Receive = {
    case s: Sample ⇒
      val newStatus = update(s, status, settings)
      context become regulating(newStatus)
      metricsCollector ! Metric.WorkQueueExpectedWaitTime(newStatus.delay)
      metricsCollector ! Metric.DropRate(newStatus.droppingRate.value)
      regulatee ! newStatus.droppingRate
  }
}

object Regulator {

  def props(settings: Settings, sampler: ActorRef, regulatee: ActorRef) =
    Props(new Regulator(settings, sampler, regulatee))

  class DroppingRate(val value: Double) extends AnyVal with Serializable

  object DroppingRate {
    def apply(value: Double): DroppingRate =
      if (value > 1) new DroppingRate(1)
      else if (value < 0) new DroppingRate(0)
      else new DroppingRate(value)
  }

  private[dispatcher] case class Status(
    delay:             FiniteDuration,
    droppingRate:      DroppingRate,
    burstDurationLeft: Duration,
    averageSpeed:      Speed,
    recordedAt:        Time           = Time.now
  )

  case class Settings(
    referenceDelay:         FiniteDuration,
    delayFactorBase:        Double,
    delayTrendFactorBase:   Double,
    durationOfBurstAllowed: FiniteDuration = Duration.Zero,
    weightOfLatestMetric:   Double         = 0.5
  )

  private[dispatcher] def estimateDelay(queueLength: QueueLength, speed: Speed): FiniteDuration = {
    ((queueLength.value / speed.value) * 1000d * 1000d).nanoseconds
  }

  private[dispatcher] def update(sample: Sample, lastStatus: Status, settings: Settings): Status = {
    import settings._
    val avgSpeed =
      Speed(
        sample.speed.value * weightOfLatestMetric + ((1d - weightOfLatestMetric) * lastStatus.averageSpeed.value)
      )

    val delay = estimateDelay(sample.queueLength, avgSpeed)

    def normalizedDelayDiffFrom(target: FiniteDuration) = (delay - target) / referenceDelay

    val factorAdjustment = if (lastStatus.droppingRate.value >= 0.1) 1
    else if (lastStatus.droppingRate.value < 0.1 && lastStatus.droppingRate.value >= 0.01)
      0.5
    else 0.125 //these hardcoded numbers are from the paper

    val droppingRateUpdate = (factorAdjustment * delayFactorBase * normalizedDelayDiffFrom(referenceDelay)) +
      (factorAdjustment * delayTrendFactorBase * normalizedDelayDiffFrom(lastStatus.delay))

    val newDropRate = DroppingRate(lastStatus.droppingRate.value + droppingRateUpdate)

    val burstDurationLeft = if (newDropRate.value == 0
      && lastStatus.delay < (referenceDelay / 2)
      && delay < (referenceDelay / 2)) durationOfBurstAllowed
    else lastStatus.burstDurationLeft - (lastStatus.recordedAt.until(Time.now))

    lastStatus.copy(
      averageSpeed = avgSpeed,
      delay = delay,
      droppingRate = newDropRate,
      burstDurationLeft = burstDurationLeft,
      recordedAt = Time.now
    )

  }
}
