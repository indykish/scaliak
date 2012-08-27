package com.stackmob.scaliak

import scalaz._
import Scalaz._
import effect._
import com.basho.riak.client.query.functions.{NamedFunction, NamedErlangFunction}
import scala.collection.JavaConverters._
import scala.collection.JavaConversions
import com.basho.riak.client.cap.{UnresolvedConflictException, Quorum}
import com.basho.riak.client.raw._
import com.basho.riak.client.query.indexes.{BinIndex, IntIndex}
import com.basho.riak.client.query.{MapReduce, BucketKeyMapReduce, BucketMapReduce}
import com.basho.riak.pbc.mapreduce.{MapReduceBuilder, JavascriptFunction}
import com.basho.riak.pbc.{RequestMeta, MapReduceResponseSource}
import com.basho.riak.pbc.MapReduceResponseSource.readAllResults
import query.indexes.{IntValueQuery, BinValueQuery, IndexQuery}
import query.LinkWalkSpec
import query.MapReduceSpec
import mapreduce._
import com.basho.riak.client.query.MapReduceResult

/**
 * Created by IntelliJ IDEA.
 * User: jordanrw
 * Date: 12/8/11
 * Time: 10:37 PM
 */

class ScaliakBucket(rawClientOrClientPool: Either[RawClient, ScaliakClientPool],
                    val name: String,
                    val allowSiblings: Boolean,
                    val lastWriteWins: Boolean,
                    val nVal: Int,
                    val backend: Option[String],
                    val smallVClock: Int,
                    val bigVClock: Int,
                    val youngVClock: Long,
                    val oldVClock: Long,
                    val precommitHooks: Seq[NamedFunction],
                    val postcommitHooks: Seq[NamedErlangFunction],
                    val rVal: Quorum,
                    val wVal: Quorum,
                    val rwVal: Quorum,
                    val dwVal: Quorum,
                    val prVal: Quorum,
                    val pwVal: Quorum,
                    val basicQuorum: Boolean,
                    val notFoundOk: Boolean,
                    val chashKeyFunction: NamedErlangFunction,
                    val linkWalkFunction: NamedErlangFunction,
                    val isSearchable: Boolean) {

  /*
   * Creates an IO action that fetches as object by key
   * The action has built-in exception handling that
   * returns a failure with the exception as the only
   * member of the exception list. For custom exception
   * handling see fetchDangerous
   */
  def fetch[T](key: String,
               r: RArgument = RArgument(),
               pr: PRArgument = PRArgument(),
               notFoundOk: NotFoundOkArgument = NotFoundOkArgument(),
               basicQuorum: BasicQuorumArgument = BasicQuorumArgument(),
               returnDeletedVClock: ReturnDeletedVCLockArgument = ReturnDeletedVCLockArgument(),
               ifModifiedSince: IfModifiedSinceArgument = IfModifiedSinceArgument(),
               ifModified: IfModifiedVClockArgument = IfModifiedVClockArgument())(implicit converter: ScaliakConverter[T], resolver: ScaliakResolver[T]): IO[ValidationNEL[Throwable, Option[T]]] = {
    fetchDangerous(key, r, pr, notFoundOk, basicQuorum, returnDeletedVClock, ifModifiedSince, ifModified) except {
      _.failureNel.point[IO]
    }

  }

  /*
   * Creates an IO action that fetches an object by key and has no built-in exception handling
   * If using this method it is necessary to deal with exception handling
   * using either the built in facilities in IO or standard try/catch
   */
  def fetchDangerous[T](key: String,
                        r: RArgument = RArgument(),
                        pr: PRArgument = PRArgument(),
                        notFoundOk: NotFoundOkArgument = NotFoundOkArgument(),
                        basicQuorum: BasicQuorumArgument = BasicQuorumArgument(),
                        returnDeletedVClock: ReturnDeletedVCLockArgument = ReturnDeletedVCLockArgument(),
                        ifModifiedSince: IfModifiedSinceArgument = IfModifiedSinceArgument(),
                        ifModified: IfModifiedVClockArgument = IfModifiedVClockArgument())(implicit converter: ScaliakConverter[T], resolver: ScaliakResolver[T]): IO[ValidationNEL[Throwable, Option[T]]] = {
    rawFetch(key, r, pr, notFoundOk, basicQuorum, returnDeletedVClock, ifModifiedSince, ifModified) map {
      riakResponseToResult(_)
    }

  }

  /*
   * Same as calling fetch and immediately calling unsafePerformIO
   * Because fetch handles exceptions this method typically will not throw
   * (but if you wish to be extra cautious it may)
   */
  def fetchUnsafe[T](key: String,
                     r: RArgument = RArgument(),
                     pr: PRArgument = PRArgument(),
                     notFoundOk: NotFoundOkArgument = NotFoundOkArgument(),
                     basicQuorum: BasicQuorumArgument = BasicQuorumArgument(),
                     returnDeletedVClock: ReturnDeletedVCLockArgument = ReturnDeletedVCLockArgument(),
                     ifModifiedSince: IfModifiedSinceArgument = IfModifiedSinceArgument(),
                     ifModified: IfModifiedVClockArgument = IfModifiedVClockArgument())(implicit converter: ScaliakConverter[T], resolver: ScaliakResolver[T]): ValidationNEL[Throwable, Option[T]] = {
    fetch(key, r, pr, notFoundOk, basicQuorum, returnDeletedVClock, ifModifiedSince, ifModified).unsafePerformIO
  }

  // ifNoneMatch - bool - store
  // ifNotModified - bool - store
  def store[T](obj: T,
               r: RArgument = RArgument(),
               pr: PRArgument = PRArgument(),
               notFoundOk: NotFoundOkArgument = NotFoundOkArgument(),
               basicQuorum: BasicQuorumArgument = BasicQuorumArgument(),
               returnDeletedVClock: ReturnDeletedVCLockArgument = ReturnDeletedVCLockArgument(),
               w: WArgument = WArgument(),
               pw: PWArgument = PWArgument(),
               dw: DWArgument = DWArgument(),
               returnBody: ReturnBodyArgument = ReturnBodyArgument(),
               ifNoneMatch: Boolean = false,
               ifNotModified: Boolean = false)(implicit converter: ScaliakConverter[T], resolver: ScaliakResolver[T], mutator: ScaliakMutation[T]): IO[ValidationNEL[Throwable, Option[T]]] = {
    //TODO: need to not convert the object here
    // it causes two calls to converter.write.
    // Instead force domain objects to implement a simple
    // interface exposing their key
    // can also make it part of the scaliak converter interface
    // and remove it from WriteObject

    val key = converter.write(obj)._key
    (for {
      resp ← rawFetch(key, r, pr, notFoundOk, basicQuorum, returnDeletedVClock)
      fetchRes ← riakResponseToResult(resp).point[IO]
    } yield {
      fetchRes flatMap {
        mbFetched ⇒ {
          val objToStore = converter.write(mutator(mbFetched, obj)).asRiak(name, resp.getVclock)
          val storeMeta = prepareStoreMeta(w, pw, dw, returnBody)
          if (ifNoneMatch) storeMeta.etags(Array(objToStore.getVtag))
          if (ifNotModified) storeMeta.lastModified(objToStore.getLastModified)

          riakResponseToResult {
            retrier[com.basho.riak.client.raw.RiakResponse] {
              rawClientOrClientPool match {
                case Left(client) ⇒ client.store(objToStore, storeMeta)
                case Right(pool) ⇒ pool.withClient[RiakResponse](_.store(objToStore, storeMeta))
              }
            }
          }
        }
      }
    }) except {
      t ⇒ t.failureNel.point[IO]
    }
  }


  /*
  * This should only be used in cases where the consequences are understood.
  * With a bucket that has allow_mult set to true, using "put" instead of "store"
  * will result in significantly more conflicts
  */
  def put[T](obj: T,
             w: WArgument = WArgument(),
             pw: PWArgument = PWArgument(),
             dw: DWArgument = DWArgument(),
             returnBody: ReturnBodyArgument = ReturnBodyArgument())(implicit converter: ScaliakConverter[T], resolver: ScaliakResolver[T]): IO[ValidationNEL[Throwable, Option[T]]] = {
    retrier[IO[com.basho.riak.client.raw.RiakResponse]] {
      rawClientOrClientPool match {
        case Left(client) ⇒ client.store(converter.write(obj).asRiak(name, null), prepareStoreMeta(w, pw, dw, returnBody)).pure[IO]
        case Right(pool) ⇒ pool.withClient[RiakResponse](_.store(converter.write(obj).asRiak(name, null), prepareStoreMeta(w, pw, dw, returnBody))).pure[IO]
      }
    } map {
      riakResponseToResult(_)
    } except {
      _.failureNel.point[IO]
    }

  }

  // r - int
  // pr - int
  // w - int
  // dw - int
  // pw - int
  // rw - int
  def delete[T](obj: T, fetchBefore: Boolean = false)(implicit converter: ScaliakConverter[T]): IO[Validation[Throwable, Unit]] = {
    deleteByKey(converter.write(obj)._key, fetchBefore)
  }

  def deleteByKey(key: String, fetchBefore: Boolean = false): IO[Validation[Throwable, Unit]] = {
    val deleteMetaBuilder = new DeleteMeta.Builder()
    val emptyFetchMeta = new FetchMeta.Builder().build()
    val mbFetchHead = {
      if (fetchBefore) {
        rawClientOrClientPool match {
          case Left(client) ⇒ client.head(name, key, emptyFetchMeta).point[Option].point[IO]
          case Right(pool) ⇒ pool.withClient[RiakResponse](_.head(name, key, emptyFetchMeta)).point[Option].point[IO]
        }
      } else none.point[IO]
    }

    val result = (for {
      mbHeadResponse ← mbFetchHead
      deleteMeta ← retrier[IO[com.basho.riak.client.raw.DeleteMeta]](prepareDeleteMeta(mbHeadResponse, deleteMetaBuilder).pure[IO])
      _ ← {
        rawClientOrClientPool match {
          case Left(client) ⇒ client.delete(name, key, deleteMeta).point[IO]
          case Right(pool) ⇒ pool.withClient[Unit](_.delete(name, key, deleteMeta)).point[IO]
        }
      }
    } yield ().success[Throwable])
    result except {
      t ⇒ t.failure.point[IO]
    }
  }

  import linkwalk._

  // This method discards any objects that have conversion errors
  def linkWalk[T](obj: ReadObject, steps: LinkWalkSteps)(implicit converter: ScaliakConverter[T]): IO[Iterable[Iterable[T]]] = {
    for {
      walkResult ← {
        rawClientOrClientPool match {
          case Left(client) ⇒ client.linkWalk(generateLinkWalkSpec(name, obj.key, steps)).point[IO]
          case Right(pool) ⇒ pool.withClient[com.basho.riak.client.query.WalkResult](_.linkWalk(generateLinkWalkSpec(name, obj.key, steps))).point[IO]
        }
      }
    } yield {
      // this is kinda ridiculous
      walkResult.asScala map {
        _.asScala map {
          converter.read(_).toOption
        } filter {
          _.isDefined
        } map {
          _.get
        }
      } filterNot {
        _.isEmpty
      }
    }
  }

  def mapReduce(job: MapReduceJob): IO[Validation[Throwable, MapReduceResult]] = {
    val jobAsJSON = mapreduce.MapReduceBuilder.toJSON(job)
    val spec = generateMapReduceSpec(jobAsJSON.toString)
    retrier {
      rawClientOrClientPool match {
        case Left(client) ⇒ client.mapReduce(spec).point[IO]
        case Right(pool) ⇒ pool.withClient[com.basho.riak.client.query.MapReduceResult](_.mapReduce(spec)).point[IO]
      }
    }.map(_.success[Throwable])
      .except {
      _.failure.point[IO]
    }
  }

  def fetchIndexByValue(index: String, value: String): IO[Validation[Throwable, List[String]]] = {
    fetchValueIndex(new BinValueQuery(BinIndex.named(index), name, value))
  }

  def fetchIndexByValue(index: String, value: Int): IO[Validation[Throwable, List[String]]] = {
    fetchValueIndex(new IntValueQuery(IntIndex.named(index), name, value))
  }

  private def generateLinkWalkSpec(bucket: String, key: String, steps: LinkWalkSteps) = {
    new LinkWalkSpec(steps, bucket, key)
  }

  private def generateMapReduceSpec(mapReduceJSONString: String) = {
    new MapReduceSpec(mapReduceJSONString)
  }

  private def fetchValueIndex(query: IndexQuery): IO[Validation[Throwable, List[String]]] = {
    rawClientOrClientPool match {
      case Left(client) ⇒ client.fetchIndex(query).point[IO].map(_.asScala.toList.success[Throwable]).except {
        _.failure.point[IO]
      }
      case Right(pool) ⇒ pool.withClient[java.util.List[java.lang.String]](_.fetchIndex(query)).point[IO].map(_.asScala.toList.success[Throwable]).except {
        _.failure.point[IO]
      }
    }
  }

  private def rawFetch(key: String,
                       r: RArgument,
                       pr: PRArgument,
                       notFoundOk: NotFoundOkArgument,
                       basicQuorum: BasicQuorumArgument,
                       returnDeletedVClock: ReturnDeletedVCLockArgument,
                       ifModifiedSince: IfModifiedSinceArgument = IfModifiedSinceArgument(),
                       ifModified: IfModifiedVClockArgument = IfModifiedVClockArgument()) = {
    val fetchMetaBuilder = new FetchMeta.Builder()
    List(r, pr, notFoundOk, basicQuorum, returnDeletedVClock, ifModifiedSince, ifModified) foreach { _ addToMeta fetchMetaBuilder }

    retrier[IO[com.basho.riak.client.raw.RiakResponse]] {
      rawClientOrClientPool match {
        case Left(client) ⇒ client.fetch(name, key, fetchMetaBuilder.build).point[IO]
        case Right(pool) ⇒ pool.withClient[RiakResponse](_.fetch(name, key, fetchMetaBuilder.build)).point[IO]
      }
    }
  }

  private def riakResponseToResult[T](r: RiakResponse)(implicit converter: ScaliakConverter[T], resolver: ScaliakResolver[T]): ValidationNEL[Throwable, Option[T]] = {
    ((r.getRiakObjects map {
      converter.read(_)
    }).toList.toNel map {
      sibs ⇒
        resolver(sibs)
    }).sequence[ScaliakConverter[T]#ReadResult, T]
  }

  private def prepareStoreMeta(w: WArgument, pw: PWArgument, dw: DWArgument, returnBody: ReturnBodyArgument) = {
    val storeMetaBuilder = new StoreMeta.Builder()
    List(w, pw, dw, returnBody) foreach { _ addToMeta storeMetaBuilder }
    storeMetaBuilder.build
  }

  private def prepareDeleteMeta(mbResponse: Option[RiakResponse], deleteMetaBuilder: DeleteMeta.Builder) = {
    val mbPrepared = for {
      response ← mbResponse
      vClock ← Option(response.getVclock)
    } yield deleteMetaBuilder.vclock(vClock)
    (mbPrepared | deleteMetaBuilder).build
  }

  private def retrier[R](f: ⇒ R, attempts: Int = 3): R = {
    try {
      f
    } catch {
      case e ⇒ {
        if (attempts == 0) {
          throw e
        } else {
          retrier(f, attempts - 1)
        }
      }
    }
  }
}

trait ScaliakConverter[T] {
  type ReadResult[T] = ValidationNEL[Throwable, T]

  def read(o: ReadObject): ReadResult[T]

  def write(o: T): WriteObject
}

object ScaliakConverter extends ScaliakConverters {
  implicit lazy val DefaultConverter = PassThroughConverter
}

trait ScaliakConverters {

  def newConverter[T](r: ReadObject ⇒ ValidationNEL[Throwable, T], w: T ⇒ WriteObject) = new ScaliakConverter[T] {
    def read(o: ReadObject) = r(o)

    def write(o: T) = w(o)
  }

  lazy val PassThroughConverter = newConverter[ReadObject](
    ((o: ReadObject) ⇒
      o.successNel[Throwable]),
    ((o: ReadObject) ⇒
      WriteObject(key = o.key, value = o.bytes, contentType = o.contentType,
        links = o.links, metadata = o.metadata, binIndexes = o.binIndexes, intIndexes = o.intIndexes,
        vTag = o.vTag, lastModified = o.lastModified)))
}

sealed trait ScaliakResolver[T] {

  def apply(siblings: NonEmptyList[ValidationNEL[Throwable, T]]): ValidationNEL[Throwable, T]

}

object ScaliakResolver extends ScaliakResolvers {

  implicit def DefaultResolver[T] = newResolver[T](
    siblings ⇒
      if (siblings.count == 1) siblings.head
      else throw new UnresolvedConflictException(null, "there were siblings", siblings.list.asJavaCollection))

}

trait ScaliakResolvers {
  def newResolver[T](resolve: NonEmptyList[ValidationNEL[Throwable, T]] ⇒ ValidationNEL[Throwable, T]) = new ScaliakResolver[T] {
    def apply(siblings: NonEmptyList[ValidationNEL[Throwable, T]]) = resolve(siblings)
  }
}

trait ScaliakMutation[T] {

  def apply(storedObject: Option[T], newObject: T): T

}

object ScaliakMutation extends ScaliakMutators {
  implicit def DefaultMutation[T] = ClobberMutation[T]
}

trait ScaliakMutators {

  def newMutation[T](mutate: (Option[T], T) ⇒ T) = new ScaliakMutation[T] {
    def apply(o: Option[T], n: T) = mutate(o, n)
  }

  def ClobberMutation[T] = newMutation((o: Option[T], n: T) ⇒ n)

}

