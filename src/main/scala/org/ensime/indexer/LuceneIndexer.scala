package org.ensime.indexer

import akka.actor.{ Props, ActorRef, ActorSystem }
import akka.util.Timeout
import org.apache.lucene.analysis.SimpleAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.index.FieldInvertState
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.DefaultSimilarity
import org.apache.lucene.search.FuzzyQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.PrefixQuery
import org.apache.lucene.search.ScoringRewrite
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.Query
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.util.Version
import java.io.File
import org.ensime.util._
import org.ensime.model.{ SymbolSearchResult, TypeSearchResult, MethodSearchResult }
import org.slf4j.LoggerFactory
import scala.collection.{ mutable, JavaConversions }
import scala.concurrent.Await
import scala.util.matching.Regex
import scala.collection.mutable.ListBuffer
import org.objectweb.asm.Opcodes
import org.json.simple._
import scala.util.Properties._
import akka.pattern.ask

object LuceneIndex extends StringSimilarity {
  val log = LoggerFactory.getLogger("LuceneIndex")
  val KeyIndexVersion = "indexVersion"
  val KeyFileHashes = "fileHashes"
  val IndexVersion: Int = 5

  val Similarity = new DefaultSimilarity {
    override def computeNorm(field: String, state: FieldInvertState): Float = {
      state.getBoost * (1F / state.getLength)
    }
  }

  def splitTypeName(nm: String): List[String] = {
    val keywords = new ListBuffer[String]()
    var i = 0
    var k = 0
    while (i < nm.length) {
      val c: Char = nm.charAt(i)
      if (Character.isUpperCase(c) && i != k) {
        keywords += nm.substring(k, i)
        k = i
      }
      i += 1
    }
    if (i != k) {
      keywords += nm.substring(k)
    }
    keywords.toList
  }

  private val cache = new mutable.HashMap[(String, String), Int]
  def editDist(a: String, b: String): Int = {
    cache.getOrElseUpdate((a, b), getLevenshteinDistance(a, b))
  }

  def isValidType(s: String): Boolean = {
    val i = s.indexOf("$")
    i == -1 || (i == (s.length - 1))
  }
  def isValidMethod(s: String): Boolean = {
    s.indexOf("$") == -1 && !s.equals("<init>") && !s.equals("this")
  }

  def loadIndexUserData(dir: File): (Int, Map[String, String]) = {
    try {
      if (dir.exists) {
        log.info(dir.toString + " exists, loading user data...")
        val index = FSDirectory.open(dir)
        val reader = IndexReader.open(index)
        log.info("Num docs: " + reader.numDocs())
        val userData = reader.getCommitUserData
        val onDiskIndexVersion = Option(
          userData.get(KeyIndexVersion)).getOrElse("0").toInt
        val src = Option(userData.get(KeyFileHashes)).getOrElse("{}")
        val indexedFiles: Map[String, String] = try {
          JSONValue.parse(src) match {
            case obj: JSONObject =>
              val jm = obj.asInstanceOf[java.util.Map[String, Object]]
              val m = JavaConversions.mapAsScalaMap[String, Object](jm)
              m.map { ea => (ea._1, ea._2.toString) }.toMap
            case _ => Map()
          }
        } catch {
          case e: Throwable => Map()
        }

        reader.close()
        (onDiskIndexVersion, indexedFiles)
      } else (0, Map())
    } catch {
      case e: Exception =>
        log.error("Exception Seen in loadIndexUserData", e)
        (0, Map())
    }
  }

  def shouldReindex(
    version: Int,
    onDisk: Set[(String, String)],
    proposed: Set[(String, String)]): Boolean = {

    // Very conservative.
    // Re-index whenever the proposed set
    // contains unindexed files.
    version < IndexVersion ||
      (proposed -- onDisk.toList).nonEmpty
  }

  def tokenize(nm: String): String = {
    val tokens = new StringBuilder
    tokens.append(nm.toLowerCase)
    tokens.append(" ")
    var i = 0
    var k = 0
    while (i < nm.length) {
      val c: Char = nm.charAt(i)
      if ((c == ' ' || c == '.') && i != k) {
        tokens.append(nm.substring(k, i))
        tokens.append(" ")
        k = i + 1
      } else if (Character.isUpperCase(c) && i != k) {
        tokens.append(nm.substring(k, i))
        tokens.append(" ")
        k = i
      }
      i += 1
    }
    if (i != k) {
      tokens.append(nm.substring(k))
    }
    tokens.toString()
  }

  def buildDoc(value: SymbolSearchResult): Document = {
    val doc = new Document()

    doc.add(new Field("tags", tokenize(value.name), Field.Store.NO, Field.Index.ANALYZED))
    doc.add(new Field("localNameTags", tokenize(value.localName), Field.Store.NO, Field.Index.ANALYZED))

    doc.add(new Field("name", value.name, Field.Store.YES, Field.Index.NOT_ANALYZED))
    doc.add(new Field("localName", value.localName, Field.Store.YES, Field.Index.NOT_ANALYZED))
    doc.add(new Field("type", value.declaredAs.toString(), Field.Store.YES, Field.Index.NOT_ANALYZED))
    value.pos match {
      case Some((file, offset)) =>
        doc.add(new Field("file", file, Field.Store.YES, Field.Index.NOT_ANALYZED))
        doc.add(new Field("offset", offset.toString, Field.Store.YES, Field.Index.NOT_ANALYZED))
      case None =>
        doc.add(new Field("file", "", Field.Store.YES, Field.Index.NOT_ANALYZED))
        doc.add(new Field("offset", "", Field.Store.YES, Field.Index.NOT_ANALYZED))
    }
    value match {
      case value: TypeSearchResult =>
        doc.add(new Field("docType", "type", Field.Store.NO, Field.Index.ANALYZED))
      case value: MethodSearchResult =>
        doc.add(new Field("owner", value.owner, Field.Store.YES, Field.Index.NOT_ANALYZED))
        doc.add(new Field("docType", "method", Field.Store.NO, Field.Index.ANALYZED))
    }
    doc
  }

  private def buildSym(d: Document): SymbolSearchResult = {
    val name = d.get("name")
    val localName = d.get("localName")
    val tpe = scala.Symbol(d.get("type"))
    val file = d.get("file")
    val offset = d.get("offset")
    val pos = (file, offset) match {
      case (file: String, "") => Some((file, 0))
      case (file: String, offset: String) => Some((file, offset.toInt))
      case _ => None
    }
    val owner = Option(d.get("owner"))
    owner match {
      case Some(owner) =>
        MethodSearchResult(name, localName, tpe, pos, owner)
      case None =>
        TypeSearchResult(name, localName, tpe, pos)
    }
  }

  def declaredAs(name: String, flags: Int): Symbol = {
    if (name.endsWith("$")) 'object
    else if ((flags & Opcodes.ACC_INTERFACE) != 0) 'trait
    else 'class
  }

  private def include(name: String,
    includes: Iterable[Regex],
    excludes: Iterable[Regex]): Boolean = {
    if (includes.isEmpty || includes.exists(_.findFirstIn(name) != None)) {
      excludes.forall(_.findFirstIn(name) == None)
    } else {
      false
    }
  }

  def buildStaticIndex(
    actorSystem: ActorSystem,
    writer: IndexWriter,
    files: Iterable[File],
    includes: Iterable[Regex],
    excludes: Iterable[Regex]) {
    val t = System.currentTimeMillis()

    /**
     * Implement a producer, consumer model for creation of the static index.
     * We fill the IndexWorkQueue's mailbox as quickly as possibly, reading
     * classes off the disk (see use of ClassIterator below). IndexWorkQueue
     * drains the mailbox as fast as it can write to the search index.
     *
     * The idea is to allow the reading of class information to proceed at
     * full speed, spooling work to the mailbox, while the index writing
     * slowly catches up.
     */
    val indexWorkQ: ActorRef = actorSystem.actorOf(Props(new IndexWorkQueue(writer)))

    class IndexingClassHandler extends ClassHandler {
      var classCount = 0
      var methodCount = 0
      var validClass = false
      override def onClass(name: String, location: String, flags: Int) {
        val isPublic = (flags & Opcodes.ACC_PUBLIC) != 0
        validClass = isPublic && isValidType(name) && include(name,
          includes, excludes)
        if (validClass) {
          indexWorkQ ! ClassEvent(name, location, flags)
          classCount += 1
        }
      }
      override def onMethod(className: String, name: String,
        location: String, flags: Int) {
        val isPublic = (flags & Opcodes.ACC_PUBLIC) != 0
        if (validClass && isPublic && isValidMethod(name)) {
          indexWorkQ ! MethodEvent(className, name, location, flags)
          methodCount += 1
        }
      }
    }
    val handler = new IndexingClassHandler

    log.info("Updated: Indexing classpath...")
    ClassIterator.findPublicSymbols(files, handler)

    import scala.concurrent.duration._
    // wait for the worker to complete
    Await.result(ask(indexWorkQ, StopEvent)(Timeout(3.hours)), Duration.Inf)
    val elapsed = System.currentTimeMillis() - t
    log.info("Indexing completed in " + elapsed / 1000.0 + " seconds.")
    log.info("Indexed " + handler.classCount + " classes with " +
      handler.methodCount + " methods.")
  }

}

class LuceneIndex {

  import LuceneIndex._

  private val analyzer = new SimpleAnalyzer(Version.LUCENE_35)
  private val config: IndexWriterConfig = new IndexWriterConfig(
    Version.LUCENE_35, analyzer)
  config.setSimilarity(Similarity)
  private var index: FSDirectory = null
  private var indexWriter: Option[IndexWriter] = None
  private var indexReader: Option[IndexReader] = None

  def initialize(
    actorSystem: ActorSystem,
    root: File,
    files: Set[File],
    includes: Iterable[Regex],
    excludes: Iterable[Regex]): Unit = {

    // TODO this should come from the config!!!
    val dir: File = new File(propOrNull("ensime.cachedir"), "lucene")

    val hashed = files.map { f =>
      if (f.exists) {
        (f.getAbsolutePath, FileUtils.md5(f))
      } else {
        (f.getAbsolutePath, "")
      }
    }
    val (version, indexedFiles) = loadIndexUserData(dir)
    log.info("ENSIME indexer version: " + IndexVersion)
    log.info("On disk version: " + version)
    log.info("On disk indexed files: " + indexedFiles.toString)

    if (shouldReindex(version, indexedFiles.toSet, hashed)) {
      log.info("Requires re-index.")
      log.info("Deleting on-disk index.")
      FileUtils.delete(dir)
    } else {
      log.info("No need to re-index.")
    }

    index = FSDirectory.open(dir)

    indexWriter = Some(new IndexWriter(index, config))
    for (writer <- indexWriter) {
      if (shouldReindex(version, indexedFiles.toSet, hashed)) {
        log.info("Re-indexing...")
        buildStaticIndex(actorSystem, writer, files, includes, excludes)
        val json = JSONValue.toJSONString(
          JavaConversions.mapAsJavaMap(hashed.toMap))
        val userData = Map[String, String](
          (KeyIndexVersion, IndexVersion.toString),
          (KeyFileHashes, json))
        writer.commit(JavaConversions.mapAsJavaMap(userData))
      }
    }

  }

  def keywordSearch(
    keywords: Iterable[String],
    maxResults: Int = 0,
    restrictToTypes: Boolean = false): List[SymbolSearchResult] = {
    var results = new ListBuffer[SymbolSearchResult]
    search(keywords, maxResults, restrictToTypes, false,
      { (r: SymbolSearchResult) =>
        results += r
      })
    results.toList
  }

  def getImportSuggestions(typeNames: Iterable[String],
    maxResults: Int = 0): List[List[SymbolSearchResult]] = {
    def suggestions(typeName: String): List[SymbolSearchResult] = {
      val keywords = typeName :: splitTypeName(typeName)
      val candidates = new mutable.HashSet[SymbolSearchResult]

      search(keywords,
        maxResults, true, true,
        {
          case r: TypeSearchResult => candidates += r
          case _ => // nothing
        })

      // Sort by edit distance of type name primarily, and
      // length of full name secondarily.
      candidates.toList.sortWith { (a, b) =>
        val d1 = editDist(a.localName, typeName)
        val d2 = editDist(b.localName, typeName)
        if (d1 == d2) a.name.length < b.name.length
        else d1 < d2
      }

    }
    typeNames.map(suggestions).toList
  }

  def insert(value: SymbolSearchResult): Unit = {
    for (w <- indexWriter) {
      val doc = buildDoc(value)
      w.updateDocument(new Term("name", value.name), doc)
    }
  }

  def remove(key: String): Unit = {
    for (w <- indexWriter) {
      val w = new IndexWriter(index, config)
      w.deleteDocuments(new Term("name", key))
    }
  }

  def commit(): Unit = {
    for (w <- indexWriter) {
      w.commit()
      for (r <- indexReader) {
        IndexReader.openIfChanged(r)
      }
    }
  }

  private def splitTypeName(nm: String): List[String] = {
    val keywords = new ListBuffer[String]()
    var i = 0
    var k = 0
    while (i < nm.length) {
      val c: Char = nm.charAt(i)
      if (Character.isUpperCase(c) && i != k) {
        keywords += nm.substring(k, i)
        k = i
      }
      i += 1
    }
    if (i != k) {
      keywords += nm.substring(k)
    }
    keywords.toList
  }

  def search(
    keys: Iterable[String],
    maxResults: Int,
    restrictToTypes: Boolean,
    fuzzy: Boolean,
    receiver: (SymbolSearchResult => Unit)): Unit = {

    val preparedKeys = keys.filter(!_.isEmpty).map(_.toLowerCase)
    val field = if (restrictToTypes) "localNameTags" else "tags"

    val bq = new BooleanQuery()
    if (restrictToTypes) {
      bq.add(new TermQuery(new Term("docType", "type")),
        BooleanClause.Occur.MUST)
    }
    for (key <- preparedKeys) {
      val term = new Term(field, key)
      val pq = if (fuzzy) new FuzzyQuery(term, 0.6F) else new PrefixQuery(term)
      /* Must force scoring, otherwise prefix queries use a constant
      score which ignores field length normalization, etc.
      http://stackoverflow.com/questions/3060636/lucene-question-of-score-caculation-with-prefixquery */
      pq.setRewriteMethod(ScoringRewrite.SCORING_BOOLEAN_QUERY_REWRITE)
      import BooleanClause._
      val operator = if (fuzzy) Occur.SHOULD else Occur.MUST
      bq.add(pq, operator)

      // val boostExacts = new TermQuery(term)
      // boostExacts.setBoost(2)
      // bq.add(boostExacts, Occur.SHOULD)
    }
    searchByQuery(bq, maxResults, receiver)
  }

  private def searchByQuery(
    q: Query,
    maxResults: Int,
    receiver: (SymbolSearchResult => Unit)): Unit = {
    if (indexReader.isEmpty) {
      indexReader = Some(IndexReader.open(index))
    }
    log.info("Handling query: " + q)
    for (reader <- indexReader) {
      val hitsPerPage = if (maxResults == 0) 100 else maxResults
      val searcher = new IndexSearcher(reader)
      searcher.setSimilarity(LuceneIndex.Similarity)
      val hits = searcher.search(q, maxResults)
      for (hit <- hits.scoreDocs) {
        val docId = hit.doc
        val doc = searcher.doc(docId)
        receiver(buildSym(doc))
      }
    }
  }

  def close() {
    for (w <- indexWriter) {
      w.close()
    }
  }
}