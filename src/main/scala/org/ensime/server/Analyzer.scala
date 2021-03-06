package org.ensime.server

import java.io.File
import akka.actor.{ ActorLogging, Actor, ActorRef }
import org.ensime.config.ProjectConfig
import org.ensime.model.SourceFileInfo
import org.ensime.model.SymbolDesignations
import org.ensime.model.OffsetRange
import org.ensime.protocol.ProtocolConversions
import org.ensime.protocol.ProtocolConst._
import org.ensime.util._
import org.slf4j.LoggerFactory
import scala.concurrent.Future
import scala.reflect.internal.util.RangePosition
import scala.tools.nsc.Settings
import scala.reflect.internal.util.OffsetPosition

case class FullTypeCheckCompleteEvent()
case class CompilerFatalError(e: Throwable)

class Analyzer(
  val project: Project,
  val indexer: ActorRef,
  val protocol: ProtocolConversions,
  val config: ProjectConfig)
    extends Actor with ActorLogging with RefactoringHandler {

  // this still doesn't silence the PresentationCompiler
  private val presCompLog = LoggerFactory.getLogger(classOf[RichPresentationCompiler])
  private val settings = new Settings(presCompLog.error)
  settings.YpresentationDebug.value = presCompLog.isTraceEnabled
  settings.YpresentationVerbose.value = presCompLog.isDebugEnabled
  settings.verbose.value = presCompLog.isDebugEnabled

  settings.processArguments(
    List("-classpath", config.compilerClasspath) ++ config.extraCompilerArgs,
    processAll = false)
  settings.usejavacp.value = false

  log.info("Presentation Compiler settings:\n" + settings)
  import protocol._

  private val reportHandler = new ReportHandler {
    override def messageUser(str: String) {
      project ! AsyncEvent(
        toWF(SendBackgroundMessageEvent(
          MsgCompilerUnexpectedError, Some(str))))
    }
    override def clearAllScalaNotes() {
      project ! AsyncEvent(toWF(ClearAllNotesEvent('scala)))
    }
    override def clearAllJavaNotes() {
      project ! AsyncEvent(toWF(ClearAllNotesEvent('java)))
    }
    override def reportScalaNotes(notes: List[Note]) {
      project ! AsyncEvent(toWF(NewNotesEvent('scala, NoteList(full = false, notes))))
    }
    override def reportJavaNotes(notes: List[Note]) {
      project ! AsyncEvent(toWF(NewNotesEvent('java, NoteList(full = false, notes))))
    }
  }

  private val reporter = new PresentationReporter(reportHandler)

  protected val scalaCompiler: RichCompilerControl = new RichPresentationCompiler(
    settings, reporter, self, indexer, config)
  protected val javaCompiler: JavaCompiler = new JavaCompiler(
    config, reportHandler, indexer)
  protected var awaitingInitialCompile = true
  protected var initTime: Long = 0

  import scalaCompiler._

  override def preStart(): Unit = {
    project.bgMessage("Initializing Analyzer. Please wait...")
    initTime = System.currentTimeMillis()

    implicit val ec = context.dispatcher

    Future {
      if (!config.disableSourceLoadOnStartup) {
        println("Building Java sources...")
        javaCompiler.compileAll()
        println("Building Scala sources...")
        reporter.disable()
        scalaCompiler.askReloadAllFiles()
        scalaCompiler.askNotifyWhenReady()
      } else {
        self ! FullTypeCheckCompleteEvent()
      }
    }
  }

  override def receive = {
    case x: Any =>
      try {
        process(x)
      } catch {
        case e: Exception =>
          log.error("Error during Analyzer message processing")
      }
  }

  def process(msg: Any): Unit = {
    msg match {
      case AnalyzerShutdownEvent() =>
        javaCompiler.shutdown()
        scalaCompiler.askClearTypeCache()
        scalaCompiler.askShutdown()
        context.stop(self)
      case FullTypeCheckCompleteEvent() =>
        if (awaitingInitialCompile) {
          awaitingInitialCompile = false
          val elapsed = System.currentTimeMillis() - initTime
          log.debug("Analyzer ready in " + elapsed / 1000.0 + " seconds.")
          reporter.enable()
          project ! AsyncEvent(toWF(AnalyzerReadyEvent()))
          indexer ! CommitReq()
        }
        project ! AsyncEvent(toWF(FullTypeCheckCompleteEvent()))

      case rpcReq @ RPCRequestEvent(req: Any, callId: Int) =>
        try {
          if (awaitingInitialCompile) {
            project.sendRPCError(ErrAnalyzerNotReady,
              Some("Analyzer is not ready! Please wait."), callId)
          } else {
            reporter.enable()

            req match {
              case RemoveFileReq(file: File) =>
                askRemoveDeleted(file)
                project ! RPCResultEvent(toWF(value = true), callId)

              case ReloadAllReq() =>
                javaCompiler.reset()
                javaCompiler.compileAll()
                scalaCompiler.askRemoveAllDeleted()
                scalaCompiler.askReloadAllFiles()
                scalaCompiler.askNotifyWhenReady()
                project ! RPCResultEvent(toWF(value = true), callId)

              case ReloadFilesReq(files) =>
                val (javas, scalas) = files.filter(_.file.exists).partition(
                  _.file.getName.endsWith(".java"))
                if (javas.nonEmpty) {
                  javaCompiler.compileFiles(javas)
                }
                if (scalas.nonEmpty) {
                  scalaCompiler.askReloadFiles(scalas.map(createSourceFile))
                  scalaCompiler.askNotifyWhenReady()
                  project ! RPCResultEvent(toWF(value = true), callId)
                }

              case PatchSourceReq(file, edits) =>
                if (!file.exists()) {
                  project.sendRPCError(ErrFileDoesNotExist,
                    Some(file.getPath), callId)
                } else {
                  val f = createSourceFile(file)
                  val revised = PatchSource.applyOperations(f, edits)
                  reporter.disable()
                  scalaCompiler.askReloadFile(revised)
                  project ! RPCResultEvent(toWF(value = true), callId)
                }

              case req: RefactorPerformReq =>
                handleRefactorRequest(req, callId)

              case req: RefactorExecReq =>
                handleRefactorExec(req, callId)

              case req: RefactorCancelReq =>
                handleRefactorCancel(req, callId)

              case CompletionsReq(file: File, point: Int,
                maxResults: Int, caseSens: Boolean, reload: Boolean) =>
                val p = if (reload) pos(file, point) else posNoRead(file, point)
                reporter.disable()
                val info = scalaCompiler.askCompletionsAt(
                  p, maxResults, caseSens)
                project ! RPCResultEvent(toWF(info), callId)

              case ImportSuggestionsReq(_, _, _, _) =>
                indexer ! rpcReq

              case PublicSymbolSearchReq(_, _) =>
                indexer ! rpcReq

              case UsesOfSymAtPointReq(file: File, point: Int) =>
                val p = pos(file, point)
                val uses = scalaCompiler.askUsesOfSymAtPoint(p)
                project ! RPCResultEvent(toWF(uses.map(toWF)), callId)

              case PackageMemberCompletionReq(path: String, prefix: String) =>
                val members = scalaCompiler.askCompletePackageMember(path, prefix)
                project ! RPCResultEvent(toWF(members.map(toWF)), callId)

              case InspectTypeReq(file: File, range: OffsetRange) =>
                val p = pos(file, range)
                val result = scalaCompiler.askInspectTypeAt(p) match {
                  case Some(info) => toWF(info)
                  case None => toWF(null)
                }
                project ! RPCResultEvent(result, callId)

              case InspectTypeByIdReq(id: Int) =>
                val result = scalaCompiler.askInspectTypeById(id) match {
                  case Some(info) => toWF(info)
                  case None => toWF(null)
                }
                project ! RPCResultEvent(result, callId)

              case SymbolAtPointReq(file: File, point: Int) =>
                val p = pos(file, point)
                val result = scalaCompiler.askSymbolInfoAt(p) match {
                  case Some(info) => toWF(info)
                  case None => toWF(null)
                }
                project ! RPCResultEvent(result, callId)

              case InspectPackageByPathReq(path: String) =>
                val result = scalaCompiler.askPackageByPath(path) match {
                  case Some(info) => toWF(info)
                  case None => toWF(null)
                }
                project ! RPCResultEvent(result, callId)

              case TypeAtPointReq(file: File, range: OffsetRange) =>
                val p = pos(file, range)
                val result = scalaCompiler.askTypeInfoAt(p) match {
                  case Some(info) => toWF(info)
                  case None => toWF(null)
                }
                project ! RPCResultEvent(result, callId)

              case TypeByIdReq(id: Int) =>
                val result = scalaCompiler.askTypeInfoById(id) match {
                  case Some(info) => toWF(info)
                  case None => toWF(null)
                }
                project ! RPCResultEvent(result, callId)

              case TypeByNameReq(name: String) =>
                val result = scalaCompiler.askTypeInfoByName(name) match {
                  case Some(info) => toWF(info)
                  case None => toWF(null)
                }
                project ! RPCResultEvent(result, callId)

              case TypeByNameAtPointReq(name: String, file: File, range: OffsetRange) =>
                val p = pos(file, range)
                val result = scalaCompiler.askTypeInfoByNameAt(name, p) match {
                  case Some(info) => toWF(info)
                  case None => toWF(null)
                }

                project ! RPCResultEvent(result, callId)

              case CallCompletionReq(id: Int) =>
                val result = scalaCompiler.askCallCompletionInfoById(id) match {
                  case Some(info) => toWF(info)
                  case None => toWF(null)
                }
                project ! RPCResultEvent(result, callId)

              case SymbolDesignationsReq(file, start, end, tpes) =>
                if (!FileUtils.isScalaSourceFile(file)) {
                  project ! RPCResultEvent(
                    toWF(SymbolDesignations(file.getPath, List())), callId)
                } else {
                  val f = createSourceFile(file)
                  val clampedEnd = math.max(end, start)
                  val pos = new RangePosition(f, start, start, clampedEnd)
                  if (tpes.nonEmpty) {
                    val syms = scalaCompiler.askSymbolDesignationsInRegion(
                      pos, tpes)
                    project ! RPCResultEvent(toWF(syms), callId)
                  } else {
                    project ! RPCResultEvent(
                      toWF(SymbolDesignations(f.path, List())), callId)
                  }
                }
            }
          }
        } catch {
          case e: Throwable =>
            log.error(e, "Error handling RPC: " + e)
            project.sendRPCError(ErrExceptionInAnalyzer,
              Some("Error occurred in Analyzer. Check the server log."), callId)
        }
      case other =>
        log.error("Unknown message type: " + other)
    }
  }

  def pos(file: File, range: OffsetRange) = {
    val f = scalaCompiler.createSourceFile(file.getCanonicalPath)
    if (range.from == range.to) new OffsetPosition(f, range.from)
    else new RangePosition(f, range.from, range.from, range.to)
  }

  def pos(file: File, offset: Int) = {
    val f = scalaCompiler.createSourceFile(file.getCanonicalPath)
    new OffsetPosition(f, offset)
  }

  def posNoRead(file: File, offset: Int) = {
    val f = scalaCompiler.findSourceFile(file.getCanonicalPath).get
    new OffsetPosition(f, offset)
  }

  def createSourceFile(file: File) = {
    scalaCompiler.createSourceFile(file.getCanonicalPath)
  }

  def createSourceFile(file: SourceFileInfo) = {
    scalaCompiler.createSourceFile(file)
  }
}

