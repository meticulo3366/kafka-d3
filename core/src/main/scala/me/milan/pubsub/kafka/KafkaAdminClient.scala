package me.milan.pubsub.kafka

import java.util.Properties

import scala.collection.JavaConverters._

import cats.effect.ConcurrentEffect
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.monadError._
import org.apache.kafka.clients.admin.AdminClientConfig._
import org.apache.kafka.clients.admin.{ AdminClient, NewTopic }

import me.milan.config.KafkaConfig
import me.milan.domain.{ Done, Error, Topic }

class KafkaAdminClient[F[_]](
  config: KafkaConfig
)(
  implicit
  E: ConcurrentEffect[F]
) {

  private val props = new Properties()
  props.setProperty(BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServer.value)

  private val adminClient: AdminClient = AdminClient.create(props)

  def createTopics: F[Done] = {

    val topicDefaultConfig: Map[String, String] = Map(
      "delete.retention.ms" → Long.MaxValue.toString,
      "retention.ms" → Long.MaxValue.toString
    )

    val newTopics = config.topics.map { topicConfig ⇒
      new NewTopic(
        topicConfig.name.value,
        topicConfig.partitions.value,
        topicConfig.replicationFactor.value.toShort
      ).configs(topicDefaultConfig.asJava)
    }

    E.async[Done] { cb ⇒
        adminClient
          .createTopics(newTopics.asJavaCollection)
          .all()
          .whenComplete { (_, throwable) ⇒
            cb(Option(throwable).toLeft(Done.instance))
          }
        ()
      }
      .handleError {
        case _: org.apache.kafka.common.errors.TopicExistsException ⇒ Done
      }
      .adaptError {
        case e ⇒ Error.System(e)
      }
  }

  def getTopics: F[Set[Topic]] =
    E.async[Set[Topic]] { cb ⇒
        adminClient.listTopics().names().whenComplete { (topics, throwable) ⇒
          cb(
            Option(throwable)
              .toLeft(topics.asScala.toSet.filterNot(_.startsWith("_")).map(Topic))
          )
        }
        ()
      }
      .adaptError {
        case e ⇒ Error.System(e)
      }

  def deleteAllTopics: F[Done] =
    for {
      topics ← getTopics
      _ ← deleteTopics(topics)
    } yield Done

  private def deleteTopics(topics: Set[Topic]): F[Done] =
    E.async[Done] { cb ⇒
        adminClient
          .deleteTopics(topics.map(_.value).asJavaCollection)
          .all
          .whenComplete { (_, throwable) ⇒
            cb(Option(throwable).toLeft(Done))
          }
        ()
      }
      .adaptError {
        case e ⇒ Error.System(e)
      }

}
