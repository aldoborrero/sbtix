package se.nullable.sbtix

import java.io.File
import java.net.{URI, URL}

import sbt.ProjectRef
import coursier._
import coursier.core.Authentication
import sbt.{Logger, ModuleID, Resolver, PatternsBasedRepository}

import scalaz.concurrent.Task
import scalaz.{-\/, EitherT, \/-}
import java.util.concurrent.ConcurrentSkipListSet

import scala.collection.JavaConverters._
import java.util.concurrent.ExecutorService

import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import java.nio.file.{StandardCopyOption, Files => NioFiles}

import matryoshka.Coalgebra
import matryoshka._
import matryoshka.data._
import matryoshka.implicits._
import se.nullable.sbtix.data.RoseTreeF

case class GenericModule(
    primaryArtifact: Artifact,
    dep: Dependency,
    localFile: java.io.File
) {
  private val isIvy    = localFile.getParentFile().getName() == "jars"
  private val moduleId = ToSbt.moduleId(dep, Map())
  val url              = new URL(primaryArtifact.url)

  private val authedUri = authed(url)

  /** remote location of the module and all related artifacts
    */
  private val calculatedParentURI = if (isIvy) {
    parentURI(parentURI(authedUri))
  } else {
    parentURI(authedUri)
  }

  /** create the authenticated version of a given url
    */
  def authed(url: URL) = {
    primaryArtifact.authentication match {
      case Some(a) =>
        new URI(
          url.getProtocol,
          s"${a.user}:${a.password}",
          url.getHost,
          url.getPort,
          url.getPath,
          url.getQuery,
          url.getRef
        )
      case None => url.toURI
    }
  }

  /** resolve the URI of a sibling artifact, based on the primary artifact's
    * parent URI
    */
  def calculateURI(f: File) =
    if (isIvy) {
      calculatedParentURI.resolve(
        f.getParentFile().getName() + "/" + f.getName()
      )
    } else {
      calculatedParentURI.resolve(f.getName())
    }

  /** local location of the module and all related artifacts
    */
  val localSearchLocation = if (isIvy) {
    localFile.getParentFile().getParentFile()
  } else {
    localFile.getParentFile()
  }
}

case class MetaArtifact(artifactUrl: String, checkSum: String)
    extends Comparable[MetaArtifact] {
  override def compareTo(other: MetaArtifact): Int = {
    return artifactUrl.compareTo(other.artifactUrl)
  }

  def matchesGenericModule(gm: GenericModule) = {
    val organ   = gm.dep.module.organization
    val name    = gm.dep.module.name
    val version = gm.dep.version

    val slashOrgans = organ.replace(".", "/")

    val mvn = s"$slashOrgans/$name/$version"
    val ivy = s"$organ/$name/$version"

    artifactUrl.contains(mvn) || artifactUrl.contains(ivy)
  }
}

class CoursierArtifactFetcher(
    logger: Logger,
    resolvers: Set[Resolver],
    credentials: Map[String, Credentials]
) {

  // Collects pom.xml and ivy.xml urls from Coursier internals
  val metaArtifactCollector = new ConcurrentSkipListSet[MetaArtifact]()

  def apply(
      depends: Set[Dependency]
  ): (Set[NixRepo], Set[NixArtifact], Set[ResolutionErrors]) = {
    val (mods, errors) = buildNixProject(depends)

    // remove metaArtifacts that we already have a module for. We do not need to look them up twice.
    val metaArtifacts = metaArtifactCollector.asScala.toSet.filterNot { meta =>
      mods.exists {
        meta.matchesGenericModule
      }
    }

    // object to work with the rootUrl of Resolvers
    val nixResolver = resolvers.map(NixResolver.resolve)

    // retrieve artifacts poms/ivys/jars
    val (repoSeq, artifactsSeqSeq) =
      nixResolver.flatMap(_.filterArtifacts(logger, mods)).unzip

    // retrieve metaArtifacts that were missed. Mostly parent POMS
    val (metaRepoSeq, metaArtifactsSeqSeq) =
      nixResolver.flatMap(_.filterMetaArtifacts(logger, metaArtifacts)).unzip

    val nixArtifacts = (artifactsSeqSeq.flatten ++ metaArtifactsSeqSeq.flatten)

    val nixRepos = (repoSeq ++ metaRepoSeq)

    (nixRepos, nixArtifacts, Set(errors))
  }

  /** modification of coursier.Cache.Fetch()
    */
  def CacheFetch_WithCollector(
      cache: File = Cache.default,
      cachePolicy: CachePolicy = CachePolicy.FetchMissing,
      checksums: Seq[Option[String]] = Cache.defaultChecksums,
      logger: Option[Cache.Logger] = None,
      pool: ExecutorService = Cache.defaultPool,
      ttl: Option[Duration] = Cache.defaultTtl
  ): Fetch.Content[Task] = { artifact =>
    Cache
      .file(
        artifact,
        cache,
        cachePolicy,
        checksums = checksums,
        logger = logger,
        pool = pool,
        ttl = ttl
      )
      .leftMap(_.describe)
      .flatMap { f =>
        def notFound(f: File) = Left(s"${f.getCanonicalPath} not found")

        def read(f: File) =
          try
            Right(
              new String(NioFiles.readAllBytes(f.toPath), "UTF-8")
                .stripPrefix("\ufeff")
            )
          catch {
            case NonFatal(e) =>
              Left(
                s"Could not read (file:${f.getCanonicalPath}): ${e.getMessage}"
              )
          }

        val res = if (f.exists()) {
          if (f.isDirectory) {
            if (artifact.url.startsWith("file:")) {

              val elements = f
                .listFiles()
                .map { c =>
                  val name = c.getName
                  val name0 =
                    if (c.isDirectory)
                      name + "/"
                    else
                      name

                  s"""<li><a href="$name0">$name0</a></li>"""
                }
                .mkString

              val page =
                s"""<!DOCTYPE html>
                   |<html>
                   |<head></head>
                   |<body>
                   |<ul>
                   |$elements
                   |</ul>
                   |</body>
                   |</html>
                 """.stripMargin

              Right(page)
            } else {
              val f0 = new File(f, ".directory")

              if (f0.exists()) {
                if (f0.isDirectory)
                  Left(s"Woops: ${f.getCanonicalPath} is a directory")
                else
                  read(f0)
              } else
                notFound(f0)
            }
          } else
            read(f)
        } else
          notFound(f)

        if (res.isRight) {
          // only collect the http and https urls
          if (artifact.url.startsWith("http")) {
            // reduce the number of tried and failed metaArtifacts by checking if Coursier succeeded in its download
            val checkSum = FindArtifactsOfRepo
              .fetchChecksum(artifact.url, "-Meta- Artifact", f.toURI().toURL())
              .get // TODO this might be expressed in a monad
            metaArtifactCollector.add(MetaArtifact(artifact.url, checkSum))
          }
        }
        EitherT.fromEither(Task.now[Either[String, String]](res))
      }
  }

  private def buildNixProject(
      modules: Set[Dependency]
  ): (Set[GenericModule], ResolutionErrors) = {
    val (dependenciesArtifacts, errors) = getAllDependencies(modules)
    val genericModules = dependenciesArtifacts.flatMap {
      case ((dependency, artifact)) =>
        val downloadedArtifact = Cache.file(artifact).run.unsafePerformSync

        downloadedArtifact.toOption.map { localFile =>
          GenericModule(artifact, dependency, localFile)
        }
    }
    (genericModules, errors)
  }

  private def getAllDependencies(
      modules: Set[Dependency]
  ): (Set[(Dependency, Artifact)], ResolutionErrors) = {

    val repos = resolvers.flatMap { resolver =>
      FromSbt.repository(
        resolver,
        ivyProps,
        logger,
        credentials.get(resolver.name).map(_.authentication)
      )
    }
    val fetch = Fetch.from(repos.toSeq, CacheFetch_WithCollector())

    def go(
        modules: Set[Dependency]
    ): (Set[(Dependency, Artifact)], ResolutionErrors) = {
      val res                 = Resolution(modules)
      val resolution          = res.process.run(fetch, 100).unsafePerformSync
      val missingDependencies = findMissingDependencies(modules, resolution)
      val resolvedMissingDependencies =
        missingDependencies.map(dep => go(Set(dep)))
      val artifacts = resolution
        .dependencyArtifacts(true)
        .toSet
        // Get sources, useful for IDE integration
        .union(
          resolution
            .dependencyClassifiersArtifacts(Seq("tests", "sources", "javadoc"))
            .toSet
        )
        .union(resolvedMissingDependencies.flatMap(_._1))
      val errors = resolvedMissingDependencies.foldRight(
        ResolutionErrors(resolution.errors)
      )((resolved, acc) => acc + resolved._2)
      (artifacts, errors)
    }

    go(modules)
  }

  private def findMissingDependencies(
      dependencies: Set[Dependency],
      resolution: Resolution
  ): Set[Dependency] = {
    dependencies.flatMap { dep =>
      if (resolution.dependencies.contains(dep)) {
        findMissingDependencies(dep, resolution)
      } else {
        Set(dep)
      }
    }
  }

  type F[X] = RoseTreeF[Dependency, X]
  type G[X] = RoseTreeF[Either[Dependency, Dependency], X]

  private def findMissingDependencies(
      module: Dependency,
      resolution: Resolution
  ): Set[Dependency] = {

    def getDeps(
        withReconciledVersions: Boolean
    ): Coalgebra[F, (Int, Dependency)] = {
      case (0, dep) =>
        RoseTreeF(dep, List.empty)
      case (n, dep) =>
        RoseTreeF(
          dep,
          resolution
            .dependenciesOf(dep, withReconciledVersions)
            .toList
            .map(d => (n - 1, d))
        )
    }

    // Get the dependency tree where versions where reconciled by coursier
    val reconciled = (100, module).ana[Fix[F]](getDeps(true))
    // Get the dependency tree with the raw ,non reconciled, versions
    val raw = (100, module).ana[Fix[F]](getDeps(false))
    // Diff the two dependency trees to find what was rejected by coursier
    val treeDiff = diffDependencyTrees(raw, reconciled)

    val possiblyMissingDependencies = treeDiff.cata[Set[Dependency]] {
      case RoseTreeF(Right(_), children)  => children.toSet.flatten
      case RoseTreeF(Left(dep), children) => children.toSet.flatten + dep
    }
    val missingDependencies =
      possiblyMissingDependencies.diff(resolution.dependencies)
    missingDependencies
  }

  private def diffDependencyTrees(raw: Fix[F], reconciled: Fix[F]): Fix[G] = {
    val coalgebra: Coalgebra[G, Either[Fix[F], (Fix[F], Fix[F])]] = {
      case Right((raw, reconciled)) =>
        val rawMap = raw.unFix.children.map { t =>
          t.unFix.value -> t.unFix.children
        }.toMap
        val recMap = reconciled.unFix.children.map { t =>
          t.unFix.value -> t.unFix.children
        }.toMap
        val diff: List[Either[Fix[F], (Fix[F], Fix[F])]] =
          rawMap.keySet
            .diff(recMap.keySet)
            .map { dep =>
              val x: Either[Fix[F], (Fix[F], Fix[F])] =
                Left(Fix[F](RoseTreeF(dep, rawMap(dep))))
              x
            }
            .toList
        val intersection: List[Either[Fix[F], (Fix[F], Fix[F])]] =
          rawMap.keySet
            .intersect(recMap.keySet)
            .map { dep =>
              val x: Either[Fix[F], (Fix[F], Fix[F])] = Right(
                (
                  Fix[F](RoseTreeF(dep, rawMap(dep))),
                  Fix[F](RoseTreeF(dep, recMap(dep)))
                )
              )
              x
            }
            .toList
        RoseTreeF(Right(raw.unFix.value), diff ++ intersection)
      case Left(raw) =>
        RoseTreeF(
          Left(raw.unFix.value),
          raw.unFix.children.map((g: Fix[F]) => Left(g))
        )
    }

    val algebra: Algebra[G, Fix[G]] = {
      case RoseTreeF(d @ Left(dep), children) =>
        Fix[G](RoseTreeF(d, children))
      case RoseTreeF(Right(dep), children) =>
        val newChildren = children.filter(_.unFix.value.isLeft)
        if (newChildren.isEmpty) {
          Fix[G](RoseTreeF(Right(dep), newChildren))
        } else {
          Fix[G](RoseTreeF(Left(dep), newChildren))
        }
    }

    val x: Either[Fix[F], (Fix[F], Fix[F])] = Right((raw, reconciled))
    x.hylo[G, Fix[G]](algebra, coalgebra)
  }

  private def ivyProps =
    Map(
      "ivy.home" -> new File(sys.props("user.home"), ".ivy2").toString
    ) ++ sys.props
}

case class ResolutionErrors(errors: Seq[(Dependency, Seq[String])]) {

  def +(other: ResolutionErrors) = {
    ResolutionErrors(errors ++ other.errors)
  }

  def +(other: Seq[ResolutionErrors]) = {
    ResolutionErrors(errors ++ other.flatMap(_.errors))
  }

}
