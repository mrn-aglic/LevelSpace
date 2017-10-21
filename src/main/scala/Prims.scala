package org.nlogo.ls

import java.lang.{Boolean => JBoolean}

import org.nlogo.api.{Argument, Command, Context, Dump, ExtensionException, Reporter}
import org.nlogo.core.{I18N, Let, LogoList, Syntax}
import org.nlogo.nvm.{Activation, ExtensionContext, Context => NvmContext}

import scala.collection.JavaConverters._
import scala.collection.mutable


object CtxConverter {
  def nvm(ctx: Context): NvmContext = ctx.asInstanceOf[ExtensionContext].nvmContext
}

class ScopedVals(elems: (Activation, AnyRef)*) extends mutable.WeakHashMap[Activation, AnyRef] {
  elems foreach { case (k, v) => this(k) = v }
}

class LetPrim extends Command {

  /**
   * In order to ensure no LS locals collide with regular locals in the
   * parent, we prefix with "ls ". This guarantees no collisions because
   * NetLogo locals are cannot contain space (and don't contain lower-case
   * letters, but I don't think that should be relied upon).
   * - BCH 10/11/2015
   **/
  val LetPrefix = "ls "

  def letBindings(ctx: NvmContext): Seq[(String, AnyRef)] = {
    ctx.activation.binding.allLets
      .filter {
        case (let: Let, _: AnyRef) => let.name != null && let.name.startsWith(LetPrefix)
      }
      .flatMap {
        case (let: Let, value: AnyRef) => toScopedVals(value).get(ctx.activation).map(let.name.substring(LetPrefix.length) -> _)
      }
  }

  override def getSyntax: Syntax = Syntax.commandSyntax(List(Syntax.SymbolType, Syntax.ReadableType))

  override def perform(args: Array[Argument], ctx: Context): Unit = {
    val token = args(0).getSymbol
    val let = Let(LetPrefix + token.text)
    val nvmCtx = CtxConverter.nvm(ctx)

    // Note that we need to replace the value in the map if the name is bound,
    // since different scopes can have the same Activation.
    // `ask` is the most common instance of this. -- BCH 1/23/2016
    try {
      val scopedVal = nvmCtx.activation.binding.getLet(let)
      toScopedVals(scopedVal)(nvmCtx.activation) = args(1).get
    } catch {
      case _: NoSuchElementException =>
        nvmCtx.activation.binding.let(let, new ScopedVals(nvmCtx.activation -> args(1).get))
    }
  }

  def toScopedVals(x: AnyRef): ScopedVals = x match {
    case vba: ScopedVals => vba
    case _ => throw new ExtensionException("Something besides an activation map was found in an LS variable. This is a bug. Please report.")
  }
}

class Ask(ls: LevelSpace) extends Command {
  override def getSyntax: Syntax =
    Syntax.commandSyntax(right = List(Syntax.NumberType | Syntax.ListType,
                                      Syntax.CodeBlockType,
                                      Syntax.RepeatableType | Syntax.ReadableType),
                         defaultOption = Some(2))

  override def perform(args: Array[Argument], ctx: Context): Unit = {
    val code = args(1).getCode.asScala.map(_.text).mkString(" ")
    val cmdArgs = args.slice(2, args.length).map(_.get)
    val lets = ls.letManager.letBindings(CtxConverter.nvm(ctx))
    ls.toModelList(args(0)).map { (m: ChildModel) =>
      m.ask(code, lets, cmdArgs)
    }.foreach(_.waitFor)
  }
}

class Report(ls: LevelSpace) extends Reporter {
  override def getSyntax: Syntax =
    Syntax.reporterSyntax(right = List(Syntax.NumberType | Syntax.ListType,
                                       Syntax.CodeBlockType,
                                       Syntax.RepeatableType | Syntax.ReadableType),
                          ret = Syntax.ReadableType,
                          defaultOption = Some(2))

  override def report(args: Array[Argument], ctx: Context): AnyRef = {
    val code = args(1).getCode.asScala.map(_.text).mkString(" ")
    val cmdArgs = args.slice(2, args.length).map(_.get)
    val lets = ls.letManager.letBindings(CtxConverter.nvm(ctx))
    val results = ls.toModelList(args(0)).map{ (m: ChildModel) =>
      m.of(code, lets, cmdArgs)
    }.map(_.waitFor)
    if (args(0).get.isInstanceOf[LogoList]) LogoList.fromVector(results.toVector) else results.head
  }
}

class Of(ls: LevelSpace) extends Report(ls) {
  override def getSyntax: Syntax =
    Syntax.reporterSyntax(left = Syntax.CodeBlockType,
                          right = List(Syntax.NumberType | Syntax.ListType),
                          ret = Syntax.ReadableType,
                          precedence = Syntax.NormalPrecedence + 1,
                          isRightAssociative = true)

  override def report(args: Array[Argument], ctx: Context): AnyRef =
    super.report(Array(args(1), args(0)), ctx)
}

class With(ls: LevelSpace) extends Reporter {
  override def getSyntax: Syntax =
    Syntax.reporterSyntax(left = Syntax.ListType,
                          right = List(Syntax.CodeBlockType),
                          ret = Syntax.ListType,
                          precedence = Syntax.NormalPrecedence + 2,
                          isRightAssociative = false)

  override def report(args: Array[Argument], ctx: Context): AnyRef = {
    val code = args(1).getCode.asScala.map(_.text).mkString(" ")
    val cmdArgs = args.slice(2, args.length).map(_.get)
    val lets = ls.letManager.letBindings(CtxConverter.nvm(ctx))
    val matchingModels = ls.toModelList(args(0))
      .map((m: ChildModel) => m -> m.of(code, lets, cmdArgs))
      .map(p => p._1 -> p._2.waitFor)
      .filter {
        case (_, b: java.lang.Boolean) => b
        case (m: ChildModel, x: AnyRef) =>
          throw new ExtensionException(I18N.errorsJ.getN("org.nlogo.prim.$common.expectedBooleanValue",
            "ls:with", m.name, Dump.logoObject(x)))
      }
      .map(_._1.modelID.toDouble: java.lang.Double)
      .toVector
    LogoList.fromVector(matchingModels)
  }
}

class ModelCommand(ls: LevelSpace, cmd: ChildModel => Unit) extends Command {
  override def getSyntax: Syntax = Syntax.commandSyntax(List(Syntax.NumberType | Syntax.ListType))
  override def perform(args: Array[Argument], ctx: Context): Unit = ls.toModelList(args(0)).foreach(cmd)
}

class ModelReporter(ls: LevelSpace, ret: Int, reporter: ChildModel => AnyRef) extends Reporter {
  override def getSyntax: Syntax = Syntax.reporterSyntax(right = List(Syntax.NumberType | Syntax.ListType), ret = ret)
  override def report(args: Array[Argument], ctx: Context): AnyRef = {
    val names = ls.toModelList(args(0)).map(reporter)
    if (args(0).get.isInstanceOf[LogoList]) LogoList.fromVector(names.toVector)
    else names.head
  }
}

class Show(ls: LevelSpace) extends ModelCommand(ls, _.show())
class Hide(ls: LevelSpace) extends ModelCommand(ls, _.hide())
class ShowAll(ls: LevelSpace) extends ModelCommand(ls, _.showAll())
class HideAll(ls: LevelSpace) extends ModelCommand(ls, _.hideAll())
class Close(ls: LevelSpace) extends ModelCommand(ls, ls.closeModel)
class UpdateView(ls: LevelSpace) extends ModelCommand(ls, {
  case hm: HeadlessChildModel => hm.updateView()
  case _ =>
})
class Name(ls: LevelSpace) extends ModelReporter(ls, Syntax.StringType, _.name)
class Path(ls: LevelSpace) extends ModelReporter(ls, Syntax.StringType, _.path)
class UsesLS(ls: LevelSpace) extends ModelReporter(ls, Syntax.BooleanType, (model: ChildModel) => Boolean.box(model.usesLevelSpace))

class SetName(ls: LevelSpace) extends Command {
  override def getSyntax: Syntax = Syntax.commandSyntax(List(Syntax.NumberType, Syntax.StringType))
  override def perform(args: Array[Argument], ctx: Context): Unit = ls.getModel(args(0).getIntValue).name = args(1).getString
}

class ModelExists(ls: LevelSpace) extends Reporter {
  override def getSyntax: Syntax = Syntax.reporterSyntax(right = List(Syntax.NumberType), ret = Syntax.BooleanType)
  override def report(args: Array[Argument], ctx: Context): JBoolean = Boolean.box(ls.containsModel(args(0).getIntValue))
}

class AllModels(ls: LevelSpace) extends Reporter {
  override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.ListType)
  override def report(args: Array[Argument], ctx: Context): LogoList =
    LogoList.fromVector(ls.modelList.asScala.map(id => Double.box(id.doubleValue)).toVector)
}

