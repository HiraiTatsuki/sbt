/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt
import java.io.InputStream

import sbt.BasicCommandStrings.ContinuousExecutePrefix
import sbt.internal.FileAttributes
import sbt.internal.LabeledFunctions._
import sbt.internal.util.{ JLine, Util }
import sbt.internal.util.complete.Parser
import sbt.internal.util.complete.Parser._
import sbt.io.FileEventMonitor.{ Creation, Deletion, Event, Update }
import sbt.util.{ Level, Logger }

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.control.NonFatal

object Watch {

  /**
   * This trait is used to control the state of [[Watch.apply]]. The [[Watch.Trigger]] action
   * indicates that [[Watch.apply]] should re-run the input task. The [[Watch.CancelWatch]]
   * actions indicate that [[Watch.apply]] should exit and return the [[Watch.CancelWatch]]
   * instance that caused the function to exit. The [[Watch.Ignore]] action is used to indicate
   * that the method should keep polling for new actions.
   */
  sealed trait Action

  /**
   * Provides a default `Ordering` for actions. Lower values correspond to higher priority actions.
   * [[CancelWatch]] is higher priority than [[ContinueWatch]].
   */
  object Action {
    implicit object ordering extends Ordering[Action] {
      override def compare(left: Action, right: Action): Int = (left, right) match {
        case (a: ContinueWatch, b: ContinueWatch) => ContinueWatch.ordering.compare(a, b)
        case (_: ContinueWatch, _: CancelWatch)   => 1
        case (a: CancelWatch, b: CancelWatch)     => CancelWatch.ordering.compare(a, b)
        case (_: CancelWatch, _: ContinueWatch)   => -1
      }
    }
  }

  /**
   * Action that indicates that the watch should stop.
   */
  sealed trait CancelWatch extends Action

  /**
   * Action that does not terminate the watch but might trigger a build.
   */
  sealed trait ContinueWatch extends Action

  /**
   * Provides a default `Ordering` for classes extending [[ContinueWatch]]. [[Trigger]] is higher
   * priority than [[Ignore]].
   */
  object ContinueWatch {

    /**
     * A default `Ordering` for [[ContinueWatch]]. [[Trigger]] is higher priority than [[Ignore]].
     */
    implicit object ordering extends Ordering[ContinueWatch] {
      override def compare(left: ContinueWatch, right: ContinueWatch): Int = left match {
        case Ignore  => if (right == Ignore) 0 else 1
        case Trigger => if (right == Trigger) 0 else -1
      }
    }
  }

  /**
   * Action that indicates that the watch should stop.
   */
  case object CancelWatch extends CancelWatch {

    /**
     * A default `Ordering` for [[ContinueWatch]]. The priority of each type of [[CancelWatch]]
     * is reflected by the ordering of the case statements in the [[ordering.compare]] method,
     * e.g. [[Custom]] is higher priority than [[HandleError]].
     */
    implicit object ordering extends Ordering[CancelWatch] {
      override def compare(left: CancelWatch, right: CancelWatch): Int = left match {
        // Note that a negative return value means the left CancelWatch is preferred to the right
        // CancelWatch while the inverse is true for a positive return value. This logic could
        // likely be simplified, but the pattern matching approach makes it very clear what happens
        // for each type of Action.
        case _: Custom =>
          right match {
            case _: Custom => 0
            case _         => -1
          }
        case _: HandleError =>
          right match {
            case _: Custom      => 1
            case _: HandleError => 0
            case _              => -1
          }
        case _: Run =>
          right match {
            case _: Run               => 0
            case CancelWatch | Reload => -1
            case _                    => 1
          }
        case CancelWatch =>
          right match {
            case CancelWatch => 0
            case Reload      => -1
            case _           => 1
          }
        case Reload => if (right == Reload) 0 else 1
      }
    }
  }

  /**
   * Action that indicates that an error has occurred. The watch will be terminated when this action
   * is produced.
   */
  final class HandleError(val throwable: Throwable) extends CancelWatch {
    override def equals(o: Any): Boolean = o match {
      case that: HandleError => this.throwable == that.throwable
      case _                 => false
    }
    override def hashCode: Int = throwable.hashCode
    override def toString: String = s"HandleError($throwable)"
  }

  /**
   * Action that indicates that the watch should continue as though nothing happened. This may be
   * because, for example, no user input was yet available.
   */
  case object Ignore extends ContinueWatch

  /**
   * Action that indicates that the watch should pause while the build is reloaded. This is used to
   * automatically reload the project when the build files (e.g. build.sbt) are changed.
   */
  case object Reload extends CancelWatch

  /**
   * Action that indicates that we should exit and run the provided command.
   *
   * @param commands the commands to run after we exit the watch
   */
  final class Run(val commands: String*) extends CancelWatch {
    override def toString: String = s"Run(${commands.mkString(", ")})"
  }
  // For now leave this private in case this isn't the best unapply type signature since it can't
  // be evolved in a binary compatible way.
  private object Run {
    def unapply(r: Run): Option[List[Exec]] = Some(r.commands.toList.map(Exec(_, None)))
  }

  /**
   * Action that indicates that the watch process should re-run the command.
   */
  case object Trigger extends ContinueWatch

  /**
   * A user defined Action. It is not sealed so that the user can create custom instances. If
   * the onStart or nextAction function passed into [[Watch.apply]] returns [[Watch.Custom]], then
   * the watch will terminate.
   */
  trait Custom extends CancelWatch

  private type NextAction = () => Watch.Action

  /**
   * Runs a task and then blocks until the task is ready to run again or we no longer wish to
   * block execution.
   *
   * @param task the aggregated task to run with each iteration
   * @param onStart function to be invoked before we start polling for events
   * @param nextAction function that returns the next state transition [[Watch.Action]].
   * @return the exit [[Watch.Action]] that can be used to potentially modify the build state and
   *         the count of the number of iterations that were run. If
   */
  def apply(task: () => Unit, onStart: NextAction, nextAction: NextAction): Watch.Action = {
    def safeNextAction(delegate: NextAction): Watch.Action =
      try delegate()
      catch { case NonFatal(t) => new HandleError(t) }
    @tailrec def next(): Watch.Action = safeNextAction(nextAction) match {
      // This should never return Ignore due to this condition.
      case Ignore => next()
      case action => action
    }
    @tailrec def impl(): Watch.Action = {
      task()
      safeNextAction(onStart) match {
        case Ignore =>
          next() match {
            case Trigger => impl()
            case action  => action
          }
        case Trigger => impl()
        case a       => a
      }
    }
    try impl()
    catch { case NonFatal(t) => new HandleError(t) }
  }

  private[sbt] object NullLogger extends Logger {
    override def trace(t: => Throwable): Unit = {}
    override def success(message: => String): Unit = {}
    override def log(level: Level.Value, message: => String): Unit = {}
  }

  /**
   * Traverse all of the events and find the one for which we give the highest
   * weight. Within the [[Action]] hierarchy:
   * [[Custom]] > [[HandleError]] > [[CancelWatch]] > [[Reload]] > [[Trigger]] > [[Ignore]]
   * the first event of each kind is returned so long as there are no higher priority events
   * in the collection. For example, if there are multiple events that all return [[Trigger]], then
   * the first one is returned. If, on the other hand, one of the events returns [[Reload]],
   * then that event "wins" and the [[Reload]] action is returned with the [[Event[FileAttributes]]] that triggered it.
   *
   * @param events the ([[Action]], [[Event[FileAttributes]]]) pairs
   * @return the ([[Action]], [[Event[FileAttributes]]]) pair with highest weight if the input events
   *         are non empty.
   */
  @inline
  private[sbt] def aggregate(
      events: Seq[(Action, Event[FileAttributes])]
  ): Option[(Action, Event[FileAttributes])] =
    if (events.isEmpty) None else Some(events.minBy(_._1))

  private implicit class StringToExec(val s: String) extends AnyVal {
    def toExec: Exec = Exec(s, None)
  }

  private[sbt] def withCharBufferedStdIn[R](f: InputStream => R): R =
    if (!Util.isWindows) JLine.usingTerminal { terminal =>
      terminal.init()
      val in = terminal.wrapInIfNeeded(System.in)
      try {
        f(in)
      } finally {
        terminal.reset()
      }
    } else
      f(System.in)

  /**
   * A constant function that returns [[Trigger]].
   */
  final val trigger: (Int, Event[FileAttributes]) => Watch.Action = {
    (_: Int, _: Event[FileAttributes]) =>
      Trigger
  }.label("Watched.trigger")

  def ifChanged(action: Action): (Int, Event[FileAttributes]) => Watch.Action =
    (_: Int, event: Event[FileAttributes]) =>
      event match {
        case Update(prev, cur, _) if prev.value != cur.value => action
        case _: Creation[_] | _: Deletion[_]                 => action
        case _                                               => Ignore
      }

  /**
   * The minimum delay between build triggers for the same file. If the file is detected
   * to have changed within this period from the last build trigger, the event will be discarded.
   */
  final val defaultAntiEntropy: FiniteDuration = 500.milliseconds

  /**
   * The duration in wall clock time for which a FileEventMonitor will retain anti-entropy
   * events for files. This is an implementation detail of the FileEventMonitor. It should
   * hopefully not need to be set by the users. It is needed because when a task takes a long time
   * to run, it is possible that events will be detected for the file that triggered the build that
   * occur within the anti-entropy period. We still allow it to be configured to limit the memory
   * usage of the FileEventMonitor (but this is somewhat unlikely to be a problem).
   */
  final val defaultAntiEntropyRetentionPeriod: FiniteDuration = 10.minutes

  /**
   * The duration for which we delay triggering when a file is deleted. This is needed because
   * many programs implement save as a file move of a temporary file onto the target file.
   * Depending on how the move is implemented, this may be detected as a deletion immediately
   * followed by a creation. If we trigger immediately on delete, we may, for example, try to
   * compile before all of the source files are actually available. The longer this value is set,
   * the less likely we are to spuriously trigger a build before all files are available, but
   * the longer it will take to trigger a build when the file is actually deleted and not renamed.
   */
  final val defaultDeletionQuarantinePeriod: FiniteDuration = 50.milliseconds

  /**
   * Converts user input to an Action with the following rules:
   * 1) 'x' or 'X' will exit sbt
   * 2) 'r' or 'R' will trigger a build
   * 3) new line characters cancel the watch and return to the shell
   */
  final val defaultInputParser: Parser[Action] = {
    val exitParser: Parser[Action] = chars("xX") ^^^ new Run("exit")
    val rebuildParser: Parser[Action] = chars("rR") ^^^ Trigger
    val cancelParser: Parser[Action] = chars(legal = "\n\r") ^^^ new Run("iflast shell")
    exitParser | rebuildParser | cancelParser
  }

  private[this] val options = {
    val enter = "<enter>"
    val newLine = if (Util.isWindows) enter else ""
    val opts = Seq(
      s"$enter: return to the shell",
      s"'r$newLine': repeat the current command",
      s"'x$newLine': exit sbt"
    )
    s"Options:\n${opts.mkString("  ", "\n  ", "")}"
  }
  private def waitMessage(project: String, commands: Seq[String]): String = {
    val plural = if (commands.size > 1) "s" else ""
    val cmds = commands.mkString("; ")
    s"Monitoring source files for updates...\n" +
      s"Project: $project\nCommand$plural: $cmds\n$options"
  }

  /**
   * A function that prints out the current iteration count and gives instructions for exiting
   * or triggering the build.
   */
  val defaultStartWatch: (Int, String, Seq[String]) => Option[String] = {
    (count: Int, project: String, commands: Seq[String]) =>
      Some(s"$count. ${waitMessage(project, commands)}")
  }.label("Watched.defaultStartWatch")

  /**
   * Default no-op callback.
   */
  val defaultOnEnter: () => Unit = () => {}

  private[sbt] val defaultCommandOnTermination: (Action, String, Int, State) => State =
    onTerminationImpl(ContinuousExecutePrefix).label("Watched.defaultCommandOnTermination")
  private[sbt] val defaultTaskOnTermination: (Action, String, Int, State) => State =
    onTerminationImpl("watch", ContinuousExecutePrefix)
      .label("Watched.defaultTaskOnTermination")

  /**
   * Default handler to transform the state when the watch terminates. When the [[Watch.Action]]
   * is [[Reload]], the handler will prepend the original command (prefixed by ~) to the
   * [[State.remainingCommands]] and then invoke the [[StateOps.reload]] method. When the
   * [[Watch.Action]] is [[HandleError]], the handler returns the result of [[StateOps.fail]].
   * When the [[Watch.Action]] is [[Watch.Run]], we add the commands specified by
   * [[Watch.Run.commands]] to the stat's remaining commands. Otherwise the original state is
   * returned.
   */
  private def onTerminationImpl(
      watchPrefixes: String*
  ): (Action, String, Int, State) => State = { (action, command, count, state) =>
    val prefix = watchPrefixes.head
    val rc = state.remainingCommands
      .filterNot(c => watchPrefixes.exists(c.commandLine.trim.startsWith))
    action match {
      case Run(commands) => state.copy(remainingCommands = commands ++ rc)
      case Reload =>
        state.copy(remainingCommands = "reload".toExec :: s"$prefix $count $command".toExec :: rc)
      case _: HandleError => state.copy(remainingCommands = rc).fail
      case _              => state.copy(remainingCommands = rc)
    }
  }

  /**
   * A constant function that always returns `None`. When
   * `Keys.watchTriggeredMessage := Watched.defaultOnTriggerMessage`, then nothing is logged when
   * a build is triggered.
   */
  final val defaultOnTriggerMessage: (Int, Event[FileAttributes], Seq[String]) => Option[String] =
    ((_: Int, e: Event[FileAttributes], commands: Seq[String]) => {
      val msg = s"Build triggered by ${e.entry.typedPath.toPath}. " +
        s"Running ${commands.mkString("'", "; ", "'")}."
      Some(msg)
    }).label("Watched.defaultOnTriggerMessage")

  final val noTriggerMessage: (Int, Event[FileAttributes], Seq[String]) => Option[String] =
    (_, _, _) => None

  /**
   * The minimum delay between file system polling when a `PollingWatchService` is used.
   */
  final val defaultPollInterval: FiniteDuration = 500.milliseconds

  /**
   * A constant function that returns an Option wrapped string that clears the screen when
   * written to stdout.
   */
  final val clearOnTrigger: Int => Option[String] =
    ((_: Int) => Some(Watched.clearScreen)).label("Watched.clearOnTrigger")
}
