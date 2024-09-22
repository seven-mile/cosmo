package cosmo

import ir._
import cosmo.system._
import cosmo.FileId
import cosmo.syntax.Ident
import cosmo.syntax.CaseBlock
import cosmo.syntax.FloatLit
import scala.annotation.tailrec

type EParam = VarExpr;
type EParams = List[EParam];

final class DefId(val id: Int) extends AnyVal {}

class Scopes {
  var scopes: List[Map[String, DefInfo]] = List(Map())

  def withScope[T](f: => T): T = {
    scopes = Map() :: scopes; val result = f; scopes = scopes.tail
    result
  }

  def get(name: String): Option[DefInfo] =
    scopes.find(_.contains(name)).flatMap(_.get(name))

  def set(name: String, value: DefInfo) =
    scopes = scopes.updated(0, scopes.head.updated(name, value))

  def remove(name: String) =
    scopes = scopes.updated(0, scopes.head.removed(name))
}

class DefInfo(
    val name: String,
    val namespaces: List[String],
    var id: DefId,
    var env: Env,
    var ty: Type = TopTy,
    var impls: List[Impl] = List(),
    var pos: Option[(Int, Int)] = None,
    var noMangle: Boolean = false,
    var isVar: Boolean = false,
    var isTypeVar: Boolean = false,
    var inClass: Boolean = false,
    var isBuiltin: Boolean = false,
    var isDependent: Boolean = true,
    var isOverride: Boolean = false,
    var isVirtual: Boolean = false,
    var isHidden: Boolean = false,
    var isMut: Boolean = true,
) {
  def defName(stem: Boolean = false): String = {
    if (noMangle) this.name
    else if (isVar || stem) this.nameStem(this.id.id)
    else this.fullMangledName(this.id.id)
  }
  def nameStem(disambiguator: Int) =
    if (noMangle) name
    else mangledName(disambiguator)
  def fullName(disambiguator: Int) =
    if (noMangle) name
    else fullMangledName(disambiguator)
  // todo: ${disambiguator}
  def mangledName(disambiguator: Int) = name
  def fullMangledName(disambiguator: Int) =
    val ens = env.fid.map(_.ns).getOrElse(List())
    ((ens ::: namespaces) :+ s"${name}").mkString("::")
  def value = env.items.get(id)
  def instantiateTy = ty // todo: instantiate type
}

object DefInfo {
  def just(id: Int, env: Env) = new DefInfo("", List(), DefId(id), env)
}

class Env(val fid: Option[FileId], val pacMgr: cosmo.PackageManager) {
  val stgE = new ExprEnv(this);

  var defAlloc = DEF_ALLOC_START
  var defs = List[DefInfo]()
  var currentDef: Option[DefId] = None
  var currentRegion = (-1, -1)
  var defParents = Map[DefId, (Option[DefId], (Int, Int))]()
  var items: Map[DefId, Item] = Map()
  var scopes = new Scopes()
  var errors: List[String] = List()
  var moduleAst: Option[Expr] = None
  var module: ir.Region = Region(List())
  var ns: List[String] = List()
  var noCore = false
  var builtinClasses = Map[Item, Class]()
  var selfRef: Option[Item] = None
  var selfImplRef: Option[Item] = None
  var rawDeps = Map[FileId, Option[Env]]()

  /// Builtin Items

  def builtins() = {
    newBuiltin("print")
    newBuiltin("println")
    newBuiltin("unreachable")
    newBuiltin("unimplemented")
    newBuiltin("panic")

    newType("c_enum", CEnumTy)
    newType("Type", UniverseTy)
    newType("bool", BoolTy)
    newType("self", SelfVal, SelfTy)
    newType("Self", SelfTy, UniverseTy)
    newType("Nothing", BottomTy)
    newType("str", StrTy)
    newType("any", TopTy)
    newType("Ref", RefTy(true, false))
    newType("Mut", RefTy(false, true))
    newType("RefMut", RefTy(true, true))
    // todo: size_t
    newType("usize", IntegerTy(64, true))
    newType("isize", IntegerTy(64, false))
    List(8, 16, 32, 64).foreach { width =>
      newType(s"i$width", IntegerTy(width, false))
      newType(s"u$width", IntegerTy(width, true))
    }
    List(32, 64, 128).foreach { width =>
      newType(s"f$width", FloatTy(width))
    }
    newType("code", Unresolved(DefInfo.just(CODE_FUNC, this)))
    this
  }

  def newBuiltin(name: String) = {
    val info = ct(name); info.noMangle = true; info.isBuiltin = true
    items += (info.id -> Opaque.expr(s"$name"))
  }

  def newType(name: String, ty: Type, tyty: Type = UniverseTy) = {
    val info = ct(name); info.noMangle = true; info.isBuiltin = true
    info.ty = tyty
    builtinClasses += (ty -> Class.empty(this, false).copy(id = info))
    items += (info.id -> ty)
  }

  /// Entry

  def entry(ast: syntax.Block): Env = {
    stgE.module = stgE.expr(ast)

    if (!noCore) then importNative(libPath("std.prelude"), Some(Ident("_")))
    val m = term(stgE.module)(0)
    if !m.isInstanceOf[Region] then err("module must be a block")
    module = m.asInstanceOf[Region]

    this
  }

  /// Item Creation

  def err(msg: String): Item =
    errors = errors :+ msg; return NoneItem

  /// Creates Def
  def ct(src: syntax.Ident | String, hidden: Boolean = false): DefInfo = {
    val (name, pos) = extractSrc(src)

    defAlloc += 1
    val info = new DefInfo(name, ns, new DefId(defAlloc), this)
    info.pos = pos
    info.isHidden = hidden
    defs = info :: defs
    defParents += (info.id -> (currentDef, currentRegion))

    if (!hidden) then scopes.set(name, info)
    info
  }

  /// Creates Infer Variable
  def createInfer(info: DefInfo, lvl: Int) = InferVar(info, level = lvl)

  /// Creates Reference
  def byRef(info: DefInfo)(implicit level: Int): Item = {
    val v = items.get(info.id).map(deref)
    debugln(s"byRef $info ${v.map(_.level)}")
    v match {
      case Some(v: Term) => v
      case _ => Term(info, v.map(_.level).getOrElse(level), value = v)
    }
  }

  def valueTermO(node: Option[Item])(implicit level: Int = 0): Item =
    node.map(valueTerm).getOrElse(NoneItem)
  def valueTerm(node: Item)(implicit level: Int = 0): Item = term(node)
  def typeTerm(node: Item)(implicit level: Int = 1): Type = term(node)
  def term(item: Item)(implicit level: Int): ir.Item = {
    if !item.isInstanceOf[Expr] then return item
    item.asInstanceOf[Expr] match {
      // control flow, todo: duplicate patterns
      case item: (Break | Continue | Opaque) => item
      case Return(v)                         => Return(term(v))
      case If(cond, x, y)        => If(term(cond), term(x), y.map(term))
      case Loop(body)            => Loop(term(body))
      case While(cond, body)     => While(term(cond), term(body))
      case For(name, iter, body) => For(name, term(iter), term(body))
      case Region(stmts)         => Region(stmts.map(term))
      // operations
      case Name(id, of)                => of.map(term).getOrElse(Unresolved(id))
      case UnOp("&", UnOp("mut", lhs)) => RefItem(term(lhs), true)
      case UnOp("&", lhs)              => RefItem(term(lhs), false)
      case UnOp("mut", lhs) =>
        errors = s"mut must be used after &" :: errors; term(lhs)
      case UnOp("*", lhs)      => derefPtr(lhs)
      case UnOp(op, lhs)       => UnOp(op, term(lhs))
      case BinOp(op, lhs, rhs) => binOp(op, term(lhs), term(rhs))
      case As(lhs, rhs)        => As(term(lhs), typeTerm(rhs))
      case KeyedArg(k, v)      => KeyedArg(term(k), term(v))
      // todo: check is compile time
      case SelectExpr(lhs, rhs) => deref(select(term(lhs), rhs))
      case Apply(lhs, rhs)      => $apply(term(lhs), rhs.map(term))
      case b: MatchExpr         => matchExpr(b)
      case ItemE(item)          => term(item)
      // declarations
      case VarExpr(id, ty, init) => varItem(id, ty, init)
      case d: DefExpr            => defItem(d)
      case c: ClassExpr          => classItem(c, Some(classItem(c, None)))
      case i: ImplExpr           => implItem(i)
      case Hole(id)              => err(s"hole $id in the air")
      case id: ParamExpr         => err(s"param $id in the air")
      case cr: CaseRegion        => err(s"case region $cr in the air")
    }
  }

  def expr(ast: syntax.Node)(implicit level: Int): ir.Item = ???

  /// imports

  def $import(p: syntax.Node, dest: Option[syntax.Node]): Item = {
    val path = p match {
      case syntax.StrLit(s) => s
      case _: (syntax.Select | syntax.Ident) =>
        return importNative(p, dest)
      case _ =>
        err("Invalid import path")
        ""
    }
    val (kind, includePath) = if path startsWith "@lib/c++/" then {
      (CModuleKind.Builtin, path.drop(9))
    } else if path.isEmpty then {
      (CModuleKind.Error, "bad import path")
    } else {
      (CModuleKind.Source, path)
    }
    val defInfo = ct("$module")
    importDest(dest, defInfo, CModule(defInfo, kind, includePath))
  }

  def importNative(p: syntax.Node, dest: Option[syntax.Node]): Item = {
    val defInfo = ct(p match {
      case _ if (!dest.isEmpty)                      => "$module"
      case syntax.Select(lhs, syntax.Ident(name), _) => name
      case syntax.Ident(name)                        => name
      case _                                         => "$module"
    })
    val fid = pacMgr.resolvePackage(p)
    val env = pacMgr.loadModule(fid)
    rawDeps += (fid -> env)
    val moduleIns = env match {
      case Some(env) =>
        NativeModule(defInfo, env)
      case None => {
        err(s"Failed to load module $p")
        Unresolved(defInfo)
      }
    }
    importDest(dest, defInfo, moduleIns)
  }

  def importDest(dest: Option[syntax.Node], defInfo: DefInfo, v: Item): Item = {
    val di = dest match {
      case Some(syntax.Ident("_")) =>
        val env = v.asInstanceOf[NativeModule].env
        val exts = env.scopes.scopes.head
        for ((name, info) <- exts.filter(!_._2.isBuiltin)) {
          items += (ct(name).id -> env.byRef(info)(0))
        }

        ct("$module")
      case Some(syntax.Ident(name)) => ct(name)
      case Some(v) =>
        err(s"Invalid import destination $v")
        ct("$module")
      case None => defInfo
    }
    di.ty = v
    v
  }

  /// Expressions

  def derefPtr(lhs: Item)(implicit level: Int): Item = {
    lhs match {
      case SelfVal => SelfVal
      case lhs     => UnOp("*", term(lhs))
    }
  }

  def deref(lhs: Item)(implicit level: Int): Item = {
    lhs match {
      case (f: Sig) if f.params.isEmpty    => $apply(f, List())
      case Fn(_, f, _) if f.params.isEmpty => $apply(f, List())
      case BoundField(_, by, _, TypeField(ev: EnumVariant))
          if ev.base.justInit =>
        $apply(lhs, List())
      case cls: Class if cls.justInit =>
        $apply(cls, List())
      case _ => lhs
    }
  }

  def binOp(op: String, lhs: Item, rhs: Item): Item = op match {
    case "<:" => Bool(isSubtype(lhs, rhs))
    case _    => BinOp(op, lhs.e, rhs.e)
  }

  def select(lhs: Item, field: String)(implicit level: Int): Item = {
    debugln(s"select $lhs ${lhs.getClass().getName()} $field")
    val x = dispatch(lhs, lhs, field, false)
    debugln(s"select $lhs $field => $x")
    x.getOrElse(Select(lhs, field))
  }

  def dispatch(lhs: Item, by: Item, field: String, casted: Boolean)(implicit
      level: Int,
  ): Option[Item] = {
    debugln(s"dispatch select($field, $casted) of $lhs by $by")
    def contDispatch(by: Item, nextCasted: Boolean) =
      if (nextCasted && casted) then None
      else dispatch(lhs, by, field, nextCasted)

    def ls(b: BoundField) = b match {
      case b @ BoundField(s @ SelfVal, _, _, DefField(f)) =>
        val s2 = f.sig.selfIsMut match {
          case Some(true)  => RefItem(s, true) // todo: check self mutability
          case Some(false) => RefItem(s, false)
          case _           => SelfTy
        }
        b.copy(lhs = s2)
      case b @ BoundField(RefItem(s @ SelfVal, isMut), _, _, DefField(f)) =>
        val s2 = f.sig.selfIsMut match {
          case Some(true) =>
            if (!isMut) {
              errors = s"self is not mutable" :: errors
            }
            RefItem(s, isMut)
          case Some(false) => RefItem(s, isMut)
          case _           => SelfTy
        }
        b.copy(lhs = s2)
      case b => b
    }
    def dFields(by: Item, v: Iterable[VField]) =
      v.find(_.name == field).map(BoundField(lhs, by, casted, _)).map(ls)

    def dImpls(id: DefInfo): Option[Item] = {
      val impls = id.impls.flatMap { i =>
        i.fields.find(_.name == field).map(BoundField(lhs, i, true, _)).map(ls)
      }
      if (impls.headOption.isDefined && !impls.tail.isEmpty) {
        errors = s"multiple impls for $field $impls" :: errors
      }
      impls.headOption
    }

    by match {
      case SelfVal =>
        selfRef match {
          case Some(r) => dispatch(lhs, r, field, casted)
          case None =>
            errors = s"no self target in this scope" :: errors; None
        }
      case RefItem(r, isMut)   => dispatch(lhs, r, field, casted)
      case Term(_, _, Some(v)) => dispatch(lhs, v, field, casted)
      case Term(id, _, None)   => dispatch(lhs, id.ty, field, casted)
      case NativeModule(info, env) =>
        return Some(env.scopes.get(field).map(env.byRef).getOrElse {
          errors = s"Undefined item $field in ${info.name}" :: errors
          Unresolved(ct(field))
        })
      case CModule(id, kind, path) => return Some(CIdent(field, List(), level))
      case CIdent(ns0, ns, lvl)    => return Some(CIdent(field, ns :+ ns0, lvl))
      case i: Var                  => dispatch(lhs, i.id.ty, field, casted)
      case i: Impl  => dFields(i, i.fields).orElse(contDispatch(i.cls, true))
      case c: Class => dFields(c, c.fields).orElse(dImpls(c.id))
      case ClassInstance(con, _) =>
        dispatch(lhs, con, field, casted)
          .orElse(con.variantOf.flatMap(dispatch(lhs, _, field, true)))
      case HKTInstance(ty, syntax) =>
        val vRes = dispatch(lhs, ty, field, casted).get
        Some(HKTInstance(vRes, Select(syntax, field)))
      case _ => None
    }
  }

  def $apply(lhs: Item, rhs: List[Item])(implicit level: Int): Item = {
    debugln(s"apply $lhs |||| ${rhs}")
    lhs match {
      case Term(id, _, Some(Unresolved(id2))) if id2.id.id == CODE_FUNC =>
        return rhs.head match {
          case Str(content) => Opaque.stmt(content)
          case e: Opaque    => e
          case _            => Opaque.expr("0 /* code */")
        }
      case Term(id, _, Some(RefTy(isRef, isMut))) =>
        assert(rhs.length == 1)
        return if (isRef) {
          val r = rhs.head;
          checkedMut(r, isMut);
          RefItem(r, isMut)
        } else {
          rhs.head
        }
      case Term(id, _, Some(v))                 => $apply(v, rhs)
      case v: CIdent if rhs.exists(_.level > 0) => CppInsType(v, rhs)
      case f: Sig                               => applyF(f, None, rhs)
      case f: Fn                                => applyF(f.sig, Some(f), rhs)
      case c: Class                             => applyC(c, Some(rhs))
      case BoundField(_, by, _, TypeField(ev: EnumVariant)) =>
        $apply(ev.base.copy(variantOf = Some(by)), rhs)
      case BoundField(that, by, _, DefField(f)) =>
        Apply(lhs.e, castArgs(f.sig.params, rhs, Some(Right(that))))
      case HKTInstance(ty, syntax) =>
        val res = hktTranspose(syntax, $apply(ty, rhs))
        if (res.level == 0) {
          res
        } else {
          HKTInstance(res, Apply(syntax, rhs))
        }
      case _ => Apply(lhs, rhs)
    }
  }

  def applyF(fn: Sig, info: Option[Fn], args: List[Item]): Item = {
    val Sig(params, ret_ty, body) = fn
    if (ret_ty.map(_.level).getOrElse(0) <= 1) {
      return Apply(info.getOrElse(fn), castArgs(fn.params, args));
    }

    implicit val level = 1;
    return scopes.withScope {
      val castedArgs = params.iterator.flatten.zip(args).map { case (p, a) =>
        val info = p.id
        scopes.set(info.name, info)
        val casted = castTo(a, info.ty)
        items += (info.id -> casted)
        // todo: constrain types
        casted
      }
      val value = body.map(lift).map(eval).getOrElse(NoneItem)
      hktRef(info, castedArgs.toList, value)
    }
  }

  def applyC(node: Class, args: Option[List[Item]]): Item = {
    debugln(s"applyClass ${node.id} ${node.variantOf} $args")
    val Class(clsInfo, params, baseArgsT, vars, fields, isAbstract, _, _) =
      node
    val baseArgs = baseArgsT.getOrElse(List())
    val isTypeLevel = params.map(_.length).map(l => baseArgs.length < l);
    args match {
      case Some(args) if isTypeLevel.getOrElse(false) =>
        return node.copy(args = Some(baseArgs ::: args))
      case Some(args)             => ClassInstance(node, args)
      case None if params.isEmpty => ClassInstance(node, List())
      case _                      => node
    };
  }

  case class MatchCaseInfo(
      destructor: syntax.Node,
      body: Option[syntax.Node],
      pattern: Item,
  );

  case class MatchInfo(
      lhs: Item,
      cases: List[MatchCaseInfo],
      defaultCase: Option[Item],
  );

  def matchExpr(b: MatchExpr)(implicit level: Int): Item = {
    // var lhs = expr(b.lhs)
    // var lhsTy = tyOf(lhs) match
    //   case None     => return err("cannot match a untyped value")
    //   case Some(ty) => ty;

    // debugln(s"matchExpr $lhs ($lhsTy) on ${b.rhs}")
    // val sCases = b.rhs match {
    //   case b: syntax.CaseBlock                  => b.stmts
    //   case b: syntax.Block if (b.stmts.isEmpty) => List()
    //   case b: syntax.Block => return err("match body contains non-case items")
    //   case _               => return err("match body must be a case block")
    // }

    // // Calculate the kind of match.
    // var defaultCase: Option[Item] = None
    // var matchCases: List[MatchCaseInfo] = List()

    // val (patterns, restTy) =
    //   sCases.foldLeft((List[(Item, Item)](), curryView(lhsTy))) {
    //     case ((patterns, lhs), syntax.Case(destructor, body)) =>
    //       val (pattern, rests) = destruct(lhs, destructor, valueTermO(body))
    //       (patterns :+ pattern, rests)
    //   }

    // checkedDestructed(restTy)
    // ValueMatch(lhs, lhsTy, patterns, Some(Unreachable))
    ???
  }

  def destruct(lhs: DestructShape, by: syntax.Node, cont: => Item)(implicit
      level: Int,
  ): ((Item, Item), DestructShape) = {

    // for (syntax.Case(destructor, body) <- sCases) {
    //   destructor match {
    //     case Ident("_") =>
    //       defaultCase match {
    //         case Some(_) =>
    //           errors = s"multiple default cases" :: errors
    //         case None =>
    //           defaultCase = valueTermO(body)
    //       }
    //     case _ =>
    //       val casePattern = destruct(lhs, destructor);
    //       matchCases =
    //         matchCases :+ MatchCaseInfo(destructor, body, casePattern)
    //   }
    // }
    val (name, args) = by match {
      // Consider destructing cases.
      case name: (syntax.Ident | syntax.Select) => (name, None)
      // TODO: nested apply matching
      case syntax.Apply(name, rhs, _) => (name, Some(rhs))
      // Matching by value, just return the value.
      case _ => return ((expr(by), cont), lhs)
    }
    val variant = enumShape(expr(name)) match {
      case Some(v)              => v
      case None if args.isEmpty =>
        // Must be resolved ident/select, also use matching by value.
        // TODO: Better fuse the check with the lines above.
        return ((expr(by), cont), lhs)
      case None =>
        err(s"Invalid enum variant $name"); return ((expr(by), cont), lhs)
    }
    // val binding = args.iterator.flatten.map {
    //   case syntax.Ident(name) => name
    //   case _                  => ""
    // }
    // EnumDestruct(lhs, variant, binding.toList, None)
    ???

    // If any of matchCases is a EnumDestruct, ...

    // val isValueMatch = matchCases.headOption match {
    //   case None                                       => ???
    //   case Some(MatchCaseInfo(_, _, _: EnumDestruct)) => false
    //   case _                                          => true
    // }

    // var vMappings =
    //   Map[String, List[(EnumDestruct, Option[syntax.Node])]]()

    // matchCases.foreach {
    //   case MatchCaseInfo(destructor, body, ed: EnumDestruct) =>
    //     // todo: stable toString
    //     val variantBase = ed.variant.variantOf.get
    //     val vs = storeTy(variantBase)
    //     vMappings.get(vs) match {
    //       case Some(lst) =>
    //         vMappings = vMappings + (vs -> (lst :+ (ed, body)))
    //       case None =>
    //         vMappings = vMappings + (vs -> List((ed, body)))
    //     }
    //   // Check if the value matches.
    //   case _ =>
    //     errors = s"not implemented mixed enum match" :: errors
    // }

    // // assert that there is only one match
    // if (vMappings.size != 1) {
    //   errors = s"not implemented mixed enum match" :: errors
    //   return NoneItem
    // }

    // val (_, cases) = vMappings.head
    // val ty = cases.head._1.variant.variantOf.get

    // debugln(s"matchExpr mappings default $defaultCase")
    // debugln(s"matchExpr mappings $ty => $cases")

    // var matchBody = List[(Class, Item)]()
    // for ((ed, body) <- cases) {
    //   val variant = ed.variant
    //   val bindings = ed.bindings

    //   val stmts = body.map(body =>
    //     scopes.withScope {
    //       // bindings
    //       variant.vars.zip(bindings).map { (vv, name) =>
    //         val defInfo = ct(name); defInfo.isVar = true
    //         defInfo.ty = vv.item.id.ty
    //         val ty: Type = defInfo.ty
    //         val tyLvl = ty.level
    //         val valLvl = (tyLvl - 1).max(0)
    //         val res = Term(defInfo, valLvl)
    //         items += (defInfo.id -> res)
    //       }
    //       List(EnumDestruct(lhs, variant, bindings, None)) :+ valueTerm(body)
    //     },
    //   );
    //   matchBody = matchBody :+ (variant, Region(stmts.getOrElse(List())))
    // }

    // val defaultCaseItem = defaultCase.getOrElse(Unreachable)
    // TypeMatch(lhs, ty, matchBody, defaultCaseItem)

  }

  /// Declarations

  def varItem(
      defInfo: DefInfo,
      oty: Option[Item],
      initExprE: Option[Item],
  )(implicit level: Int): ir.Var = {
    defInfo.isVar = true;
    val initExpr = initExprE.map(normalize)
    val initTy = (oty.map(typeTerm), defInfo.name) match {
      case (Some(ty), _)  => Some(ty)
      case (None, "self") => Some(RefItem(SelfTy, false))
      case _ => {
        initExpr match {
          case Some(initExpr) => tyOf(initExpr)
          case None =>
            errors =
              s"either typing or initial expression must be provided for variable $defInfo" :: errors
            None
        }
      }
    }
    val valLvl = (initTy.map(_.level).getOrElse(0) - 1).max(0)
    val res = ir.Var(defInfo, initExpr, valLvl)
    defInfo.ty = initTy.getOrElse(createInfer(defInfo, valLvl + 1))
    items += (defInfo.id -> initExpr.getOrElse(res))
    items += (defInfo.id -> byRef(defInfo))
    res
  }

  def defItem(e: DefExpr, withBody: Boolean = true) = {
    debugln(s"defItem ${e.id.name}")
    val DefExpr(info, params, constraints, ret_ty, rhs) = e
    val sig = Sig(
      resolveParams(params),
      ret_ty.map(typeTerm),
      None,
    )
    val f = Fn(info, sig, sig.resolveLevel)
    items += (info.id -> f)

    if (withBody) {
      val annotated = sig.ret_ty
      val body = rhs.map(e => normalize(valueTerm(e)))
      val bodyTy = body.flatMap(tyOf)
      debugln(s"defItem $info, $bodyTy <: $annotated")
      // we have already checked annotated <: bodyTy when we are
      // making valueTerm.
      val sigRetTy = annotated.orElse(bodyTy)

      val sig2 = sig.copy(body = body, ret_ty = sigRetTy)
      val l2 = sig2.resolveLevel;

      info.isDependent =
        if l2 > 0 then body.map(isDependent).getOrElse(false) else false

      val f2 = f.copy(sig = sig2, level = l2)
      items += (info.id -> f2)
      f2
    } else {
      f
    }
  }

  def classItem(e: ClassExpr, classSelf: Option[Class] = None): Class = {
    val ClassExpr(info, params, constraints, body, isAbstract) = e
    val ss = selfRef
    val cls = {
      classSelf.foreach(cls => selfRef = Some(cls))
      val (vars, restFields) = body match
        case _: CaseRegion if isAbstract =>
          errors = "Cannot have an enumerated trait" :: errors; (List(), List())
        case body: Region =>
          baseClass(body, classSelf.map(p => p.vars ::: p.restFields))
        case caseBlock: CaseRegion =>
          (List(), enumClass(caseBlock, info, classSelf.map(_.restFields)))
        case _ =>
          val kind = if isAbstract then "trait" else "class"
          errors = s"Invalid $kind body" :: errors; (List(), List())
      if (isAbstract) {
        restFields.foreach {
          case DefField(f) => f.id.isVirtual = true
          case _           =>
        }
      }
      Class(info, resolveParams(params), None, vars, restFields, isAbstract)
    }
    info.ty = cls
    selfRef = ss
    items += (info.id -> cls)

    // Check conflict
    var existings = Map[String, VField]();
    def addField(f: VField) = if (existings.contains(f.name)) {
      errors = s"conflict field ${f.name}" :: errors
    } else {
      existings += (f.name -> f)
    }
    cls.fields.foreach(addField)

    cls
  }

  def baseClass(ast: Region, fields: Option[List[VField]]) = {
    val withBody = !fields.isEmpty
    var vars = List[VarField]();
    var rests = List[VField]();

    ast.stmts.foreach {
      // todo: syntax.Typ
      case VarExpr(x, ty, y) =>
        x.inClass = true;
        vars = vars :+ VarField(varItem(x, ty, y.map(valueTerm))(0))
      case d: DefExpr =>
        d.id.inClass = true;
        rests = rests :+ DefField(defItem(d, withBody = withBody))
      case node =>
        errors = s"Invalid class item $node" :: errors
    }

    (vars, rests)
  }

  def enumClass(
      ast: CaseRegion,
      info: DefInfo,
      fields: Option[List[VField]],
  ) = {
    var subs = fields.iterator.flatten
      .filter(_.isInstanceOf[TypeField])
      .map(_.item.asInstanceOf[EnumVariant])
    var selfRestFields2 = fields.map(_.filter(!_.isInstanceOf[TypeField]))

    val stmts: List[VField] = ast.cases
      .filter(!_._1.isWildcard)
      .map(p =>
        TypeField(
          enumVariant(p, info, info.name, classSelf = subs.nextOption()),
        ),
      )
    val restFields = ast.cases
      .find(_._1.isWildcard)
      .map { case (_, body) =>
        baseClass(
          // todo: as instance of Block
          body.getOrElse(Region(List())).asInstanceOf[Region],
          fields = selfRestFields2,
        )
      } match {
      case Some((vars, restFields)) =>
        if (!vars.isEmpty) {
          errors = s"Invalid default class for enum" :: errors
        }
        restFields
      case None => List()
    }

    restFields ::: stmts
  }

  def enumVariant(
      node: (Expr, Option[Expr]),
      baseId: DefInfo,
      baseName: String,
      classSelf: Option[EnumVariant] = None,
  ) = {
    // val (cond, body) = node;
    // val (subName, params) = cond match {
    //   case name: Name                    => (name.id, List())
    //   case Apply(name: Name, params) => (name.id, params)
    //   case _                             => (ct("invalid"), List())
    // }

    // val vars = params.zipWithIndex.map {
    //   case (n: syntax.Ident, index) =>
    //     val ty = if (n.name == baseName) { syntax.Ident("Self") }
    //     else { n }
    //     syntax.Var(Ident(s"_${index}"), Some(ty), None)
    //   // todo: replace self
    //   case (n: syntax.Apply, index) =>
    //     syntax.Var(Ident(s"_${index}"), Some(n), None)
    //   case (_, index) => syntax.Var(Ident(s"_${index}"), None, None)
    // }

    // val b = (body, vars) match {
    //   case (_, Nil) => body.getOrElse(syntax.Block(List()))
    //   case (Some(syntax.Block(bc)), vars) => syntax.Block(vars ::: bc)
    //   case (Some(n), vars)                => syntax.Block(vars :+ n)
    //   case _                              => syntax.Block(vars)
    // }

    // val cls = classItem(
    //   // todo: trait enum
    //   ClassExpr(subName, params, b, false),
    //   classSelf = classSelf.map(_.base),
    // )
    // // todo: right way to hide it
    // cls.vars.foreach { v => v.item.id.isHidden = true }
    // val info = ct(subName); info.inClass = true; cls.id.inClass = true;
    // EnumVariant(info, cls)
    ???
  }

  def implItem(ast: ImplExpr) = {
    val ImplExpr(info, params, constraints, i, c, body) = ast
    val ss = selfImplRef
    val ss2 = selfRef
    val impl = {
      val (iface, cls) = (i.map(typeTerm), typeTerm(c))
      selfRef = Some(cls)
      val defs = body match {
        case body: Region =>
          selfImplRef = Some(
            Impl(info, resolveParams(params), iface.get, cls, List()),
          )
          val (vars, decls) = baseClass(body, None)
          if (!vars.isEmpty) {
            errors = s"impl cannot have vars" :: errors
          }
          selfImplRef = Some(
            Impl(info, resolveParams(params), iface.get, cls, decls),
          )
          baseClass(body, Some(vars ::: decls))._2
        case _ => errors = s"Invalid impl body" :: errors; List()
      }
      defs.foreach { d => d.item.id.isOverride = true }
      Impl(info, resolveParams(params), iface.get, cls, defs)
    }
    selfRef = ss2
    selfImplRef = ss
    items += (info.id -> impl)
    associateImpl(impl, impl.cls)
    impl
  }

  def associateImpl(impl: Impl, cls: Type): Unit = {
    if (canonicalTy(cls).isBuilitin) {
      return associateImpl(impl, classRepr(cls))
    }

    debugln(s"associateImpl $impl to $cls")
    val id = cls match {
      case cls: Class => cls.id
      case v: Term    => v.id
      case _ =>
        errors = s"Invalid impl target $cls" :: errors
        return
    }
    id.impls = id.impls :+ impl
  }

  def hktRef(
      f: Option[Fn],
      args: List[Item],
      value: Item,
  ): Item = {
    if f.isEmpty then return value
    def ins(ty: Type) = HKTInstance(ty, Apply(f.get, args))
    value match {
      case _: (CIdent | CppInsType | ClassInstance) => value
      case i: Class                                 => ins(i)
      case _ =>
        throw new Exception(
          s"cannot ref dependent type $value ${value.getClass().getName()}",
        )
    }
  }

  def hktTranspose(syntax: Item, res: Item): Item = {
    debugln(s"hktTranspose $syntax $res")
    res match {
      case ClassInstance(con, args) =>
        val hktCon = con.copy(resolvedAs = Some(syntax))
        ClassInstance(hktCon, args)
      case _ => ???
    }
  }

  def castArgs(
      eParams: Option[List[Param]],
      eArgs: List[Item],
      self: Option[Either[Unit, Item]] = None,
  ): List[Item] = {
    val params = eParams.map(_.filter(_.level == 0)).map { p =>
      self match {
        case None => p
        case Some(_) =>
          if (p.headOption.exists(_.id.name == "self")) {
            p.tail
          } else {
            p
          }
      }
    }

    val firstArgs = eArgs.takeWhile(_.level > 0)
    val args = eArgs.drop(firstArgs.length)

    val paramsLength = params.map(_.length).getOrElse(0)
    if (paramsLength != args.length) {
      println(("self", self))
      errors =
        s"Invalid number of arguments (${paramsLength} v.s. ${args.length}) $params v.s. $args" :: errors
      return args
    }

    var argsPair = params.iterator.flatten.zip(args);
    firstArgs ::: (argsPair.map { case (p, a) =>
      val info = p.id
      val casted = castTo(a, info.ty)
      items += (info.id -> casted)
      // todo: cast type
      casted
    }.toList)
  }

  def castTo(item: Item, nty: Type): Item = {
    val ty = canonicalTy(nty)
    debugln(s"castTo $item to $nty ($ty)")
    ty match {
      case TopTy | UniverseTy => item
      case RefItem(rty, rhsIsMut) =>
        item match {
          case l: RefItem => {
            if (rhsIsMut) {
              checkedMut(item, rhsIsMut);
            }
            if (rhsIsMut && !l.isMut) {
              errors = s"Cannot cast to mut ref" :: errors
              return Opaque.expr(
                s"\n#error \"Cannot cast to mut ref\"\n /* ref $item */",
              )
            }
            val lty = canonicalTy(item)
            val lIsR = isSubtype(lty, rty);
            val rIsL = isSubtype(rty, lty);
            debugln(s"castTo $item to $rty ($lty) $lIsR $rIsL")
            if (lIsR && rIsL) {
              debugln(s"$item is exact $rty")
              return item
            }
            if (!lIsR) {
              errors = s"No way to cast $item ($lty) to $nty ($rty)" :: errors
              return Opaque.expr(
                s"\n#error \"No way to cast\"\n /* ref $item */",
              )
            }

            rty match {
              case tr: Class if tr.isAbstract => {
                As(item.e, implClass(lty, tr).get.e)
              }
              case _ =>
                As(item.e, nty.e)
            }
          }
          case l: Str if isSubtype(rty, StrTy) => RefItem(l, false)
          case l if !rhsIsMut                  => castTo(RefItem(l, false), nty)
          case _ =>
            errors = s"Must ref item $item" :: errors;
            Opaque.expr(s"\n#error \"Must ref item\"\n /* ref $item */")
        }
      case _ => return item
    }
  }

  def defByName(info: DefInfo): String = info.defName(stem = false)

  def varByRef(vv: Term): String = {
    val ir.Term(id, level, v) = vv
    v.map {
      case v: CppInsType => Some(storeTy(v))
      case v: CIdent     => Some(v.repr)
      case _             => None
    }.flatten
      .getOrElse(defByName(id))
  }

  def storeTy(ty: Type): String = {
    debugln(s"storeTy $ty")
    ty match {
      case IntegerTy(size, isUnsigned) =>
        s"${if (isUnsigned) "u" else ""}int${size}_t"
      case FloatTy(size)   => s"float${size}_t"
      case UnitTy          => "void"
      case BoolTy          => "bool"
      case StrTy           => "::str"
      case SelfTy          => "self_t"
      case TopTy           => "auto"
      case BottomTy        => "void"
      case ty: Integer     => "int32_t"
      case ty: Str         => "::str"
      case ty: CIdent      => ty.repr
      case ty: CppInsType  => ty.repr(storeTy)
      case ty: HKTInstance => ty.repr(storeTy)
      case ty: TupleLit =>
        s"std::tuple<${ty.elems.map(storeTy).mkString(", ")}>"
      case cls: Class if cls.resolvedAs.isDefined =>
        (cls.variantOf, cls.resolvedAs) match {
          case (Some(v), Some(Select(lhs, _))) => storeTy(HKTInstance(v, lhs))
          case _ => storeTy(HKTInstance(cls, cls.resolvedAs.get))
        }
      case cls: Class if cls.variantOf.isDefined => storeTy(cls.variantOf.get)
      case cls: Class                            => cls.repr(storeTy)
      case v: Term if v.value.isEmpty            => v.id.defName(stem = false)
      case v: Var if v.init.isEmpty              => v.id.defName(stem = false)
      case v: Fn                                 => v.id.defName(stem = false)
      case Term(_, _, Some(v))                   => storeTy(v)
      case RefItem(lhs, isMut) =>
        s"${if (isMut) "" else "const "}${storeTy(lhs)}&"
      case Apply(lhs, rhs) => {
        val lhsTy = storeTy(lhs)
        val rhsTy = rhs.map(storeTy).mkString(", ")
        s"$lhsTy<$rhsTy>"
      }
      case ty => "auto"
    }
  }

  def checkedMut(item: Item, isMut: Boolean): Unit = {
    debugln(s"checkedMut $item $isMut")
    val lhsIsMut = item match {
      case RefItem(lhs, rhsIsMut) => return checkedMut(lhs, rhsIsMut)
      case SelfTy                 => return
      case v: Term                => v.id.isMut
      case v: Var                 => v.id.isMut
      case _                      => return
    }
    if (!lhsIsMut && isMut) {
      errors = s"Cannot cast to mut ref" :: errors
    }
  }

  // : Normalization Part

  def eval(item: Item)(implicit level: Int): Item = {
    debugln(s"eval $item $level")
    val e = eval;
    item match {
      case CppInsType(target, arguments) => CppInsType(target, arguments.map(e))
      case Term(id, lvl, value) if level <= lvl => items(id.id)
      case _                                    => item
    }
  }

  def normalize(body: Item): Item = {
    debugln(s"normalize $body")
    body match {
      case _ => body
    }
  }

  // : Type Checker Part

  def isDependent(body: Item): Boolean = {
    body match {
      case _: (CIdent | CppInsType) =>
        false
      case _ => true
    }
  }

  def eqType(lhs: Item, rhs: Item): Boolean = {
    val lty = canonicalTy(lhs)
    val rty = canonicalTy(rhs)
    isSubtype(lty, rty) && isSubtype(rty, lty)
  }

  def canonicalTy(rhs: Item): Item = {
    debugln(s"canonicalTy $rhs")
    rhs match {
      case SelfVal if selfImplRef.isDefined =>
        selfRef.map(canonicalTy).getOrElse(TopTy)
      case SelfVal                               => SelfTy
      case Term(_, level, Some(v)) if level == 1 => canonicalTy(v)
      case v: Term                               => canonicalTy(v.id.ty)
      case v: Var                                => canonicalTy(v.id.ty)
      case RefItem(lhs, isMut) => RefItem(canonicalTy(lhs), isMut)
      case _                   => rhs
    }
  }

  def isSubtype(lhs: Item, rhs: Item): Boolean = {
    debugln(s"isSubtype $lhs $rhs")
    rhs match {
      case Term(_, _, Some(v)) => isSubtype(lhs, v)
      case RefItem(rhs, isMut) => isSubtype(lhs, rhs)
      // todo: same level
      case cls: Class         => implClass(lhs, cls).isDefined
      case TopTy | UniverseTy => true
      case BottomTy           => false
      case StrTy | BoolTy if isBuiltin(lhs, rhs) => true
      case _ => {
        lhs match {
          case Term(_, _, Some(v)) => isSubtype(v, rhs)
          case RefItem(lhs, isMut) => isSubtype(lhs, rhs)
          case BottomTy            => true
          case StrTy | BoolTy      => isBuiltin(lhs, rhs)
          case _                   => false
        }
      }
    }
  }

  def isBuiltin(lhs: Item, rhs: Item): Boolean = {
    debugln(s"isBuiltin $lhs $rhs")
    lhs match {
      case Term(_, _, Some(v)) => isBuiltin(v, rhs)
      case TopTy | UniverseTy  => true
      case BottomTy            => true
      case Bool(_)             => lhs == rhs || rhs == BoolTy
      case Str(_)              => lhs == rhs || rhs == StrTy
      case _                   => lhs == rhs
    }
  }

  def classRepr(lhs: Type): Class = {
    lhs match {
      case ClassInstance(con, _) => con
      case v: Class              => v
      case Term(_, _, Some(v))   => classRepr(v)
      case RefItem(lhs, isMut)   => classRepr(lhs)
      case _ if lhs.isBuilitin   => builtinClasses(lhs)
      case l @ (Bool(_))         => builtinClasses(l.ty)
      case l @ (Str(_))          => builtinClasses(l.ty)
      case l @ (Integer(_))      => builtinClasses(l.ty)
      case Unresolved(_)         => Class.empty(this, false)
      case _ => throw new Exception(s"cannot get class $lhs")
    }
  }

  def implClass(lhs: Item, goal: Class): Option[Item] = {
    val cls = classRepr(lhs)
    debugln(
      s"implClass? $goal(${goal.isAbstract}) for $lhs ($cls) impls ${cls.id.impls} ",
    )
    if (cls.id == goal.id) {
      return Some(cls)
    }
    if (!goal.isAbstract) {
      return None;
    }
    cls.id.impls.find { i => isSubtype(i.iface, goal) }
  }

  enum DestructShape {
    case Ty(ty: Type);
  }

  def checkedDestructed(shape: DestructShape) = {
    shape match {
      case DestructShape.Ty(ty) =>
        ty match
          case BottomKind(_) =>
          case ty =>
            err(s"required destructed type, but got $ty")
    }
  }

  def curryView(ty: Type): DestructShape = {
    // DestructShape.Ty(ty)
    ???
  }

  def enumShape(ty: Item): Option[Class] = {
    ty match {
      case v: Term if v.value.isEmpty =>
        enumShape(items.getOrElse(v.id.id, NoneItem))
      case Term(_, _, Some(v)) => enumShape(v)
      case BoundField(_, by, _, TypeField(v: EnumVariant)) =>
        Some(v.base.copy(variantOf = Some(by)))
      case ClassInstance(con, _)             => enumShape(con)
      case v: Class if v.variantOf.isDefined => Some(v)
      case ty                                => None
    }
  }

  def tyOf(lhs: Item): Option[Type] = {
    debugln(s"tyOf $lhs")
    lhs match {
      case _: Integer          => Some(IntegerTy(32, false))
      case _: Rune             => Some(IntegerTy(32, false))
      case _: Str              => Some(StrTy)
      case _: (Apply | Select) => Some(TopTy)
      case _: (While | Loop | For | Break | Continue) => Some(UnitTy)
      case Unreachable                                => Some(BottomTy)
      case _: (CIdent | Class | CppInsType) =>
        Some(UniverseTy)
      case RefItem(lhs, isMut)              => tyOf(lhs).map(RefItem(_, isMut))
      case v: ClassInstance                 => Some(v.con)
      case BoundField(_, _, _, VarField(v)) => Some(v.id.ty)
      case b: BinOp                         => coerce(tyOf(b.lhs), tyOf(b.rhs))
      case If(_, x, y)                      => coerce(tyOf(x), y.flatMap(tyOf))
      case SelfVal                          => Some(SelfTy)
      case Term(id, _, Some(v))             => tyOf(v)
      case Term(id, level, _) if level == 0 => Some(id.ty)
      case v: Var =>
        debugln(s"tyOf(Var) ${v.id.ty}")
        Some(v.id.ty)
      case TodoLit => Some(BottomTy)
      case reg: Region => {
        reg.stmts.lastOption match {
          case None    => Some(UnitTy)
          case Some(v) => tyOf(v)
        }
      }
      case TypeMatch(_, _, cases, d) => {
        val types = (cases.map(_._2) :+ d).map(tyOf).flatten
        debugln(s"coerce enumMatch $types")
        types.lastOption
      }
      case ValueMatch(_, _, cases, d) => {
        val types = (cases.map(_._2).map(tyOf) :+ d.flatMap(tyOf)).flatten
        debugln(s"coerce valueMatch $types")
        types.lastOption
      }
      case _ =>
        throw new Exception(
          s"program is not well typed, because of $lhs (${lhs.getClass().getName()}).",
        )
    }
  }

  def lift(item: Item): Type = {
    debugln(s"lift $item")
    item match {
      case item: CIdent => CIdent(item.name, item.ns, 1)
      case item: CppInsType =>
        CppInsType(
          lift(item.target).asInstanceOf[CIdent],
          item.arguments.map(lift),
        )
      case SelfVal => SelfTy
      case _       => item
    }
  }

  def coerce(lhs: Option[Type], rhs: Option[Type]): Option[Type] = {
    // todo: corece correctly
    lhs.orElse(rhs)
  }

  // todo: meaninglessly resolve
  def resolveParams(params: Option[EParams]) = params.map { params =>
    params.map { p => Param(p.id, (p.id.ty.level - 1).max(0)) }
  }

  def findItem(offset: Int): Option[Item] =
    logln(s"findItem in $fid with offset $offset")
    val node = nodeCovering(offset)
    logln(s"findItem: $offset $node")
    val id = node.flatMap(n => defs.find(_.pos.contains((n.offset, n.end))))
    logln(s"findItem ID: $offset $id")
    id.map(id => byRef(id)((id.ty.level - 1).max(0)))

  lazy val deps: List[(FileId, Option[Env])] = {
    rawDeps.iterator.toList.sortBy(_._1.toString)
  }

  lazy val nodes: Array[syntax.Node] = {
    // var nodes = scala.collection.mutable.ArrayBuilder.make[syntax.Node]
    // def go(node: syntax.Node): Unit =
    //   nodes += node; node.children.foreach(go)
    // moduleAst.foreach(go)
    // nodes.result().sortBy(_.offset)
    ???
  }

  def nodeCovering(offset: Int): Option[syntax.Node] = {
    logln(s"nodeLowBound(offset) = ${nodeLowBound(offset).map(nodes)}")
    @tailrec
    def go(index: Int, offset: Int): Option[syntax.Node] =
      if (nodes(index).offset <= offset && nodes(index).end >= offset)
        return Some(nodes(index))
      if (index == 0) then return None else go(index - 1, offset)
    nodeLowBound(offset).flatMap(go(_, offset))
  }

  def nodeLowBound(offset: Int): Option[Int] = {
    import scala.collection.Searching._
    var i = Ident("")
    i.offset = offset

    object ByOffset extends Ordering[syntax.Node] {
      def compare(x: syntax.Node, y: syntax.Node) = x.offset - y.offset
    }

    nodes.search(i)(ByOffset) match {
      case Found(i)                   => Some(i)
      case InsertionPoint(i) if i > 0 => Some(i - 1)
      case _                          => None
    }
  }
}

def xId(src: syntax.Ident | String) = src match {
  case node @ syntax.Ident(name) => name
  case name: String              => name
}

def extractSrc(src: syntax.Ident | String) = src match {
  case node @ syntax.Ident(name) => (name, Some(node.offset, node.end))
  case name: String              => (name, None)
}
